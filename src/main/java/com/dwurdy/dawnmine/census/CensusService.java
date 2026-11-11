package com.dwurdy.dawnmine.census;

import com.dwurdy.dawnmine.config.DawnmineConfig;
import com.dwurdy.dawnmine.generation.OreCatalog;
import com.dwurdy.dawnmine.run.MineManager;
import com.dwurdy.dawnmine.topology.HubLayout;
import com.dwurdy.dawnmine.topology.TopologyProfiles;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;

/**
 * Read-only census of the prepared mine. Scans the real blocks (not any
 * generator plan) a slice of chunks per server tick, then analyses off-thread.
 * Writes nothing: it never edits blocks, entities or saved data, and it draws
 * no loot (containers are read through their NBT, never opened).
 */
public final class CensusService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long TICK_BUDGET_NANOS = 12_000_000L;
    private static final int WINDOW = 32;
    private static final int ISSUE_PER_TICK = 4;
    /** Abort rather than risk the server: minimum heap headroom while scanning. */
    private static final long MIN_FREE_HEAP_BYTES = 768L * 1024 * 1024;
    private static Job current;
    /** Holds a column loaded for the whole time it is scanned (block entities are dropped
     * when a chunk unloads); released immediately after the scan. */
    private static final net.minecraft.server.level.TicketType<net.minecraft.world.level.ChunkPos> CENSUS_TICKET =
            net.minecraft.server.level.TicketType.create("dawnmine_census",
                    java.util.Comparator.comparingLong(net.minecraft.world.level.ChunkPos::toLong));
    private static java.util.concurrent.ExecutorService analysisExecutor;

    private static synchronized java.util.concurrent.ExecutorService executor() {
        if (analysisExecutor == null) {
            analysisExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "dawnmine-census");
                t.setDaemon(true);
                return t;
            });
        }
        return analysisExecutor;
    }

    /** Drops any running job and the analysis thread (server stopping). */
    public static synchronized void clear() {
        if (current != null) {
            current.cancelled = true;
            current.releaseAll();
        }
        current = null;
        if (analysisExecutor != null) {
            analysisExecutor.shutdownNow();
            analysisExecutor = null;
        }
    }

    private CensusService() { }

    /** Starts a census. Returns a refusal message, or null when it started. */
    public static String start(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        MineManager manager = MineManager.get(server);
        if (current != null) {
            return "A census is already running (" + current.progress() + ").";
        }
        if (!manager.data().status().entryAllowed()) {
            return "The mine is locked or resetting; census not started.";
        }
        if (!manager.preparationComplete()) {
            return "The mine is not ready (run is not fully prepared); census not started.";
        }
        ServerLevel mine = server.getLevel(MineManager.MINE_DIMENSION);
        if (mine == null) {
            return "The mine dimension is not loaded; census not started.";
        }
        current = new Job(server, mine, source, DawnmineConfig.mineRegionRadiusChunks());
        return null;
    }

    public static void onServerTick(MinecraftServer server) {
        Job job = current;
        if (job == null || job.server != server) {
            return;
        }
        if (job.analysing) {
            // current stays set until the analysis has reported, but a reset or low heap
            // while analysing discards the result instead of posting a dead run's report
            String abort = job.abortReason();
            if (abort != null && !job.cancelled) {
                job.cancelled = true;
                current = null;
                LOGGER.warn("[dawnmine] census aborted: {}", abort);
                job.source.sendFailure(Component.literal("Census aborted: " + abort));
            }
            return;
        }
        try {
            String abort = job.abortReason();
            if (abort != null) {
                current = null;
                job.releaseAll();
                LOGGER.warn("[dawnmine] census aborted: {}", abort);
                job.source.sendFailure(Component.literal("Census aborted: " + abort));
                return;
            }
            if (job.step()) {
                job.analysing = true;
                job.finish();
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            current = null;
            job.releaseAll();
            LOGGER.error("[dawnmine] census failed", e);
            job.source.sendFailure(Component.literal("Census failed: " + e));
        }
    }

    private static final class Job {
        final MinecraftServer server;
        final ServerLevel level;
        final CommandSourceStack source;
        final int radius;
        final int width;
        final int total;
        final CensusSnapshot snapshot;
        final Set<Block> oreBlocks = new HashSet<>();
        final Map<Block, String> oreIds = new HashMap<>();
        record Pending(net.minecraft.world.level.ChunkPos pos,
                CompletableFuture<net.minecraft.server.level.ChunkResult<ChunkAccess>> future) { }

        final java.util.ArrayDeque<Pending> pending = new java.util.ArrayDeque<>();
        final int bandMinY;
        final int bandMaxY;
        volatile boolean cancelled;
        final String runId;
        volatile boolean analysing;
        int nextIndex;
        int index;
        int missing;

        Job(MinecraftServer server, ServerLevel level, CommandSourceStack source, int radius) {
            this.server = server;
            this.level = level;
            this.source = source;
            this.radius = radius;
            this.width = radius * 2;
            this.total = width * width;
            List<CensusSnapshot.Band> bands = new ArrayList<>();
            for (var region : TopologyProfiles.active().regions()) {
                bands.add(new CensusSnapshot.Band(region.id(), region.bottomY(), region.topY()));
            }
            // The first layer's caves reach above the old profile's top band; cover them.
            if (!bands.isEmpty()) {
                CensusSnapshot.Band first = bands.get(0);
                bands.set(0, new CensusSnapshot.Band(first.id(), first.minY(), Math.max(first.maxY(), 127)));
            }
            this.snapshot = new CensusSnapshot(bands);
            this.bandMinY = bands.stream().mapToInt(CensusSnapshot.Band::minY).min().orElse(0);
            this.bandMaxY = bands.stream().mapToInt(CensusSnapshot.Band::maxY).max().orElse(-1);
            this.runId = MineManager.get(server).data().runId();
            OreCatalog.active().entries().forEach((id, entry) -> {
                Block block = BuiltInRegistries.BLOCK.get(id);
                if (block != null && block != net.minecraft.world.level.block.Blocks.AIR) {
                    oreBlocks.add(block);
                    oreIds.put(block, id.toString());
                }
            });
            source.sendSuccess(() -> Component.literal("Census started: scanning " + total
                    + " chunk columns a few per tick; the result will be posted here."), false);
        }

        String progress() {
            return analysing ? "analysing" : index + "/" + total + " chunk columns";
        }

        /** Non-null when the scan must stop: the run changed or reset, or heap is low. */
        String abortReason() {
            MineManager manager = MineManager.get(server);
            if (!runId.equals(manager.data().runId()) || !manager.preparationComplete()
                    || !manager.data().status().entryAllowed()) {
                return "the mine was reset or regenerated during the scan; results discarded";
            }
            Runtime rt = Runtime.getRuntime();
            long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
            if (free < MIN_FREE_HEAP_BYTES) {
                return "server heap is low (" + (free >> 20) + " MB free); census stopped to protect the server";
            }
            return null;
        }

        /** Requests a bounded window of chunk loads (the same async FULL loads the
         * generator's pregen uses; every column already exists once preparation is
         * complete, so nothing new is generated) and scans completed ones in order
         * until the tick budget is spent. True when every column is processed. */
        boolean step() {
            long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
            int issued = 0;
            while (nextIndex < total && pending.size() < WINDOW && issued++ < ISSUE_PER_TICK
                    && System.nanoTime() < deadline) {
                int cx = nextIndex % width - radius;
                int cz = nextIndex / width - radius;
                var pos = new net.minecraft.world.level.ChunkPos(cx, cz);
                level.getChunkSource().addRegionTicket(CENSUS_TICKET, pos, 0, pos);
                pending.add(new Pending(pos,
                        level.getChunkSource().getChunkFuture(cx, cz, ChunkStatus.FULL, true)));
                nextIndex++;
            }
            while (!pending.isEmpty() && pending.peek().future().isDone()
                    && System.nanoTime() < deadline) {
                Pending done = pending.poll();
                ChunkAccess chunk = null;
                try {
                    var result = done.future().getNow(null);
                    chunk = result == null ? null : result.orElse(null);
                    if (chunk == null) {
                        missing++;
                    } else {
                        scanChunk(chunk);
                    }
                } catch (java.util.concurrent.CompletionException
                        | java.util.concurrent.CancellationException e) {
                    LOGGER.warn("[dawnmine] census could not load a chunk column: {}", e.toString());
                    missing++;
                } finally {
                    level.getChunkSource().removeRegionTicket(CENSUS_TICKET, done.pos(), 0, done.pos());
                }
                index++;
            }
            return index >= total;
        }

        /** Releases every ticket still held by unscanned columns. */
        void releaseAll() {
            for (Pending p : pending) {
                level.getChunkSource().removeRegionTicket(CENSUS_TICKET, p.pos(), 0, p.pos());
            }
            pending.clear();
        }

        private void scanChunk(ChunkAccess chunk) {
            int cx = chunk.getPos().x;
            int cz = chunk.getPos().z;
            snapshot.addChunk();
            LevelChunkSection[] sections = chunk.getSections();
            int minSection = level.getMinSection();
            for (int i = 0; i < sections.length; i++) {
                LevelChunkSection section = sections[i];
                int baseY = (minSection + i) << 4;
                if (section == null || baseY + 15 < bandMinY || baseY > bandMaxY
                        || !section.maybeHas(this::interesting)) {
                    continue; // air above or below the mine's bands is not mine space
                }
                for (int yy = 0; yy < 16; yy++) {
                    for (int zz = 0; zz < 16; zz++) {
                        for (int xx = 0; xx < 16; xx++) {
                            BlockState state = section.getBlockState(xx, yy, zz);
                            int wx = (cx << 4) + xx;
                            int wy = baseY + yy;
                            int wz = (cz << 4) + zz;
                            if (!state.getFluidState().isEmpty()) {
                                snapshot.addFluid(wx, wy, wz);
                            } else if (!state.blocksMotion()) {
                                snapshot.addOpen(wx, wy, wz);
                            } else {
                                String ore = oreIds.get(state.getBlock());
                                if (ore != null) {
                                    snapshot.addOre(wx, wy, wz, ore);
                                }
                            }
                        }
                    }
                }
            }
            for (BlockEntity be : chunk.getBlockEntitiesPos().stream().map(chunk::getBlockEntity).filter(java.util.Objects::nonNull).toList()) {
                BlockPos pos = be.getBlockPos();
                if (inHub(pos)) {
                    continue; // hub Dispatch barrel and consoles are not mine content
                }
                if (be instanceof SpawnerBlockEntity) {
                    snapshot.addMob(pos.getX(), pos.getY(), pos.getZ());
                } else if (be instanceof RandomizableContainerBlockEntity && holdsLoot(be)) {
                    snapshot.addLoot(pos.getX(), pos.getY(), pos.getZ());
                }
            }
        }

        private boolean interesting(BlockState state) {
            return !state.blocksMotion() || oreBlocks.contains(state.getBlock());
        }

        /** Reads container NBT only: never opens it, so no loot table is rolled. */
        private boolean holdsLoot(BlockEntity be) {
            CompoundTag tag = be.saveWithoutMetadata(level.registryAccess());
            return tag.contains("LootTable") || !tag.getList("Items", 10).isEmpty();
        }

        private static boolean inHub(BlockPos p) {
            return p.getX() >= HubLayout.SAFE_MIN.x() && p.getX() <= HubLayout.SAFE_MAX.x()
                    && p.getY() >= HubLayout.SAFE_MIN.y() && p.getY() <= HubLayout.SAFE_MAX.y()
                    && p.getZ() >= HubLayout.SAFE_MIN.z() && p.getZ() <= HubLayout.SAFE_MAX.z();
        }

        void finish() {
            BlockPos recorded = MineManager.get(server).data().mineArrival();
            BlockPos start = recorded != null ? recorded : MineManager.ARRIVAL;
            Job self = this;
            int missingChunks = missing;
            source.sendSuccess(() -> Component.literal("Census scan done; analysing..."), false);
            CompletableFuture.supplyAsync(() -> CensusAnalyzer.analyze(snapshot,
                            start.getX(), start.getY(), start.getZ(), CensusAnalyzer.Params.DEFAULT),
                            executor())
                    .whenComplete((report, error) -> server.execute(() -> {
                        if (current == self) {
                            current = null;
                        }
                        if (cancelled) {
                            return; // aborted (reset, low heap, shutdown): never post a stale report
                        }
                        if (error != null) {
                            LOGGER.error("[dawnmine] census analysis failed", error);
                            source.sendFailure(Component.literal("Census analysis failed: " + error));
                            return;
                        }
                        List<String> lines = new ArrayList<>(report.lines());
                        lines.add("note: 'mobs' counts spawner blocks only (live mobs are not part of the "
                                + "world's static content); missingChunks=" + missingChunks);
                        for (String line : lines) {
                            LOGGER.info("[dawnmine] census {}", line);
                            source.sendSuccess(() -> Component.literal(line), false);
                        }
                    }));
        }
    }
}
