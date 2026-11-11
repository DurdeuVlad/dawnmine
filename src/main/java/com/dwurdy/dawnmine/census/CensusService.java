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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
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
    private static final int WINDOW = 64;
    private static final int ISSUE_PER_TICK = 16;
    private static Job current;

    private CensusService() { }

    /** Starts a census. Returns a refusal message, or null when it started. */
    public static String start(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        MineManager manager = MineManager.get(server);
        if (current != null) {
            return "A census is already running (" + current.progress() + ").";
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
        try {
            if (job.step()) {
                current = null;
                job.finish();
            }
        } catch (RuntimeException e) {
            current = null;
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
        final java.util.ArrayDeque<CompletableFuture<net.minecraft.server.level.ChunkResult<ChunkAccess>>> pending =
                new java.util.ArrayDeque<>();
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
            this.snapshot = new CensusSnapshot(bands);
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
            return index + "/" + total + " chunk columns";
        }

        /** Requests a bounded window of chunk loads (the same async FULL loads the
         * generator's pregen uses; every column already exists once preparation is
         * complete, so nothing new is generated) and scans completed ones in order
         * until the tick budget is spent. True when every column is processed. */
        boolean step() {
            long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
            int issued = 0;
            while (nextIndex < total && pending.size() < WINDOW && issued++ < ISSUE_PER_TICK) {
                int cx = nextIndex % width - radius;
                int cz = nextIndex / width - radius;
                pending.add(level.getChunkSource().getChunkFuture(cx, cz, ChunkStatus.FULL, true));
                nextIndex++;
            }
            while (!pending.isEmpty() && pending.peek().isDone() && System.nanoTime() < deadline) {
                var result = pending.poll().getNow(null);
                ChunkAccess chunk = result == null ? null : result.orElse(null);
                if (chunk == null) {
                    missing++;
                } else {
                    scanChunk(chunk);
                }
                index++;
            }
            return index >= total;
        }

        private void scanChunk(ChunkAccess chunk) {
            int cx = chunk.getPos().x;
            int cz = chunk.getPos().z;
            snapshot.addChunk();
            LevelChunkSection[] sections = chunk.getSections();
            int minSection = level.getMinSection();
            for (int i = 0; i < sections.length; i++) {
                LevelChunkSection section = sections[i];
                if (section == null || !section.maybeHas(this::interesting)) {
                    continue;
                }
                int baseY = (minSection + i) << 4;
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
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Mob) {
                    BlockPos p = entity.blockPosition();
                    snapshot.addMob(p.getX(), p.getY(), p.getZ());
                }
            }
            BlockPos start = MineManager.ARRIVAL;
            int missingChunks = missing;
            source.sendSuccess(() -> Component.literal("Census scan done; analysing..."), false);
            CompletableFuture.supplyAsync(() -> CensusAnalyzer.analyze(snapshot,
                            start.getX(), start.getY(), start.getZ(), CensusAnalyzer.Params.DEFAULT))
                    .whenComplete((report, error) -> server.execute(() -> {
                        if (error != null) {
                            LOGGER.error("[dawnmine] census analysis failed", error);
                            source.sendFailure(Component.literal("Census analysis failed: " + error));
                            return;
                        }
                        List<String> lines = new ArrayList<>(report.lines());
                        lines.add("note: mobs/spawners count loaded entities and spawner blocks only; "
                                + "missingChunks=" + missingChunks);
                        for (String line : lines) {
                            LOGGER.info("[dawnmine] census {}", line);
                            source.sendSuccess(() -> Component.literal(line), false);
                        }
                    }));
        }
    }
}
