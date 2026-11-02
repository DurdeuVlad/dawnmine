package com.rusticcraft.publicmines.generation;

import com.mojang.logging.LogUtils;
import com.rusticcraft.publicmines.config.PublicMineConfig;
import com.rusticcraft.publicmines.run.MineManager;
import com.rusticcraft.publicmines.run.MineRunData;
import com.rusticcraft.publicmines.run.OwnedVolume;
import com.rusticcraft.publicmines.run.RunStatus;
import com.rusticcraft.publicmines.topology.MineTopology;
import com.rusticcraft.publicmines.topology.TopoEdge;
import com.rusticcraft.publicmines.topology.TopoNode;
import com.rusticcraft.publicmines.topology.TopoPos;
import com.rusticcraft.publicmines.topology.TopologyPlan;
import com.rusticcraft.publicmines.topology.TopologyProfiles;
import com.rusticcraft.publicmines.topology.TopologyReport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Applies shared, deterministic mine segments in small server-thread slices.
 * The scheduler never asks Minecraft to load a chunk: it pauses at an
 * unloaded operation until normal player ticketing makes that chunk visible.
 */
public final class MineGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();
    // Block updates can trigger lighting and neighboring-chunk work. Keep both
    // a strict operation ceiling and a soft elapsed-time ceiling per tick.
    private static final int OPERATIONS_PER_TICK = 8;
    private static final long SLICE_BUDGET_NANOS = 5_000_000L;
    private static final int FRONTIER_SCAN_TICKS = 20;
    private static final int PLAN_CHECK_TICKS = 200;
    private static final int FRONTIER_DISTANCE = 28;
    private static final TopoPos ARRIVAL = new TopoPos(
            MineManager.ARRIVAL.getX(), MineManager.ARRIVAL.getY(), MineManager.ARRIVAL.getZ());
    private static final String GEOMETRY_VERSION = "public-mines-segment-geometry-v1";

    private static MineGenerator instance;

    private final MinecraftServer server;
    private final MineManager manager;
    private TopologyPlan topology;
    private String loadedRunId = "";
    private String loadedTopologyFingerprint = "";
    private long lastPlanCheck = Long.MIN_VALUE;
    private long lastFrontierScan = Long.MIN_VALUE;
    private Map<String, TopoEdge> edgesBySegmentId = Map.of();
    private Map<Long, List<TopoNode>> nodesByChunk = Map.of();
    private List<OwnedVolume> ownedVolumes = List.of();
    private SegmentGeometry.SegmentPlan activePlan;
    private String profiledSegmentId = "";
    private long segmentElapsedNanos;
    private long segmentMaxSliceNanos;
    private int segmentOperations;
    private int segmentWrites;
    private int segmentMaxSliceOperations;
    private long segmentMaxOperationNanos;

    private MineGenerator(MinecraftServer server) {
        this.server = server;
        this.manager = MineManager.get(server);
    }

    public static synchronized MineGenerator get(MinecraftServer server) {
        if (instance == null || instance.server != server) {
            instance = new MineGenerator(server);
        }
        return instance;
    }

    /** Called once the saved run has been loaded by the server. */
    public synchronized void onServerStarted() {
        topology = null;
        activePlan = null;
        lastFrontierScan = server.getTickCount() - FRONTIER_SCAN_TICKS;
        MineRunData data = manager.data();
        data.restartActiveGenerationSegmentForReplay();
        if (refreshPlan(true)) {
            ensureBootstrapQueued();
        }
    }

    /** ServerTickEvent.Post handler. Mutations are capped by operation count. */
    public synchronized void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer() != server) {
            return;
        }
        MineRunData data = manager.data();
        if (data.status() != RunStatus.READY) {
            return;
        }

        long tick = server.getTickCount();
        if (topology == null || !loadedRunId.equals(data.runId())
                || tick - lastPlanCheck >= PLAN_CHECK_TICKS) {
            if (!refreshPlan(false)) {
                return;
            }
        }
        ensureBootstrapQueued();
        if (tick - lastFrontierScan >= FRONTIER_SCAN_TICKS) {
            lastFrontierScan = tick;
            enqueueNearbyFrontierEdges(data, manager.mineLevel());
        }
        processOneSlice(data, manager.mineLevel());
    }

    private boolean refreshPlan(boolean startup) {
        MineRunData data = manager.data();
        ServerLevel mine = manager.mineLevel();
        if (data.status() != RunStatus.READY || mine == null) {
            return false;
        }
        try {
            TopologyPlan planned = MineTopology.plan(data, mine);
            TopologyReport report = MineTopology.validate(planned);
            if (!report.ok()) {
                manager.failGeneration("topology validation failed: " + report.describe());
                LOGGER.error("[publicmines] Refusing generation for invalid topology: {}", report.describe());
                return false;
            }
            if (!planned.fingerprint().equals(data.topologyFingerprint())) {
                manager.failGeneration("active topology differs from the saved run receipt");
                LOGGER.error("[publicmines] Refusing generation because the active topology fingerprint differs from the run receipt.");
                return false;
            }

            boolean netherrackEnabled = data.generationPlanFingerprint().isEmpty()
                    ? PublicMineConfig.netherrackEnabled() : data.generationNetherrackEnabled();
            int netherrackInterval = data.generationPlanFingerprint().isEmpty()
                    ? PublicMineConfig.netherrackColumnInterval() : data.generationNetherrackInterval();
            String generationFingerprint = generationFingerprint(planned,
                    netherrackEnabled, netherrackInterval);
            if (!data.beginGeneration(generationFingerprint, netherrackEnabled, netherrackInterval)) {
                manager.failGeneration("saved generation plan fingerprint does not match the active geometry settings; start a new run");
                LOGGER.error("[publicmines] Refusing generation because the saved geometry plan fingerprint has drifted.");
                return false;
            }
            if (!loadedTopologyFingerprint.equals(planned.fingerprint()) || !loadedRunId.equals(data.runId())) {
                topology = planned;
                loadedRunId = data.runId();
                loadedTopologyFingerprint = planned.fingerprint();
                activePlan = null;
                edgesBySegmentId = indexEdges(planned);
                nodesByChunk = indexNodes(planned);
                ownedVolumes = List.copyOf(data.ownedVolumes());
            }
            lastPlanCheck = server.getTickCount();
            if (startup) {
                data.restartActiveGenerationSegmentForReplay();
            }
            return true;
        } catch (RuntimeException e) {
            manager.failGeneration("could not validate the saved plan: " + e.getMessage());
            LOGGER.error("[publicmines] Refusing mine generation after plan validation error.", e);
            return false;
        }
    }

    private void ensureBootstrapQueued() {
        if (topology == null) {
            return;
        }
        MineRunData data = manager.data();
        data.enqueueGenerationSegment("connector:arrival:" + topology.entranceId());
    }

    private void enqueueNearbyFrontierEdges(MineRunData data, ServerLevel mine) {
        if (mine == null || topology == null) {
            return;
        }
        Set<String> completed = new HashSet<>(data.completedGenerationSegments());
        Set<String> completedNodes = new HashSet<>();
        for (String id : completed) {
            if (id.startsWith("node:")) {
                completedNodes.add(id.substring("node:".length()));
            }
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.serverLevel() != mine) {
                continue;
            }
            int chunkX = player.blockPosition().getX() >> 4;
            int chunkZ = player.blockPosition().getZ() >> 4;
            for (int dz = -2; dz <= 2; dz++) {
                for (int dx = -2; dx <= 2; dx++) {
                    List<TopoNode> candidates = nodesByChunk.get(chunkKey(chunkX + dx, chunkZ + dz));
                    if (candidates == null) {
                        continue;
                    }
                    for (String edgeId : FrontierPlanner.adjacentEdges(topology, candidates,
                            completedNodes, player.getX(), player.getY(), player.getZ(),
                            FRONTIER_DISTANCE)) {
                        data.enqueueGenerationSegment(edgeId);
                    }
                }
            }
        }
    }

    private void processOneSlice(MineRunData data, ServerLevel mine) {
        if (mine == null) {
            return;
        }
        String segmentId = data.beginNextGenerationSegment();
        if (segmentId.isEmpty()) {
            activePlan = null;
            return;
        }
        try {
            if (activePlan == null || !activePlan.id().equals(segmentId)) {
                activePlan = createPlan(segmentId);
                profiledSegmentId = segmentId;
                segmentElapsedNanos = 0;
                segmentMaxSliceNanos = 0;
                segmentOperations = 0;
                segmentWrites = 0;
                segmentMaxSliceOperations = 0;
                segmentMaxOperationNanos = 0;
            }
            int cursor = data.activeGenerationCursor();
            int total = activePlan.operations().size();
            if (cursor > total) {
                failSegment(data, "saved cursor " + cursor + " exceeds segment " + segmentId
                        + " operation count " + total);
                return;
            }
            if (cursor == total) {
                finishSegment(data, segmentId);
                return;
            }

            List<SegmentGeometry.BlockOperation> slice = activePlan.operations(cursor, OPERATIONS_PER_TICK);
            long sliceStarted = System.nanoTime();
            int processed = 0;
            int writes = 0;
            for (SegmentGeometry.BlockOperation operation : slice) {
                TopoPos position = operation.position();
                BlockPos blockPos = new BlockPos(position.x(), position.y(), position.z());
                if (!insideOwnedVolume(mine, blockPos)) {
                    failSegment(data, "segment " + segmentId + " attempted an out-of-ownership write at "
                            + MineManager.describePos(blockPos));
                    return;
                }
                if (SegmentGeometry.isArrivalKeepOut(position, ARRIVAL)) {
                    failSegment(data, "segment " + segmentId + " overlapped the arrival pad at "
                            + MineManager.describePos(blockPos));
                    return;
                }
                if (!mine.getChunkSource().hasChunk(blockPos.getX() >> 4, blockPos.getZ() >> 4)) {
                    break;
                }
                long operationStarted = System.nanoTime();
                if (applyOperation(mine, blockPos, operation.kind(), position.y())) {
                    writes++;
                }
                segmentMaxOperationNanos = Math.max(segmentMaxOperationNanos,
                        System.nanoTime() - operationStarted);
                processed++;
                if (System.nanoTime() - sliceStarted >= SLICE_BUDGET_NANOS) {
                    break;
                }
            }
            if (processed > 0) {
                long elapsed = System.nanoTime() - sliceStarted;
                segmentElapsedNanos += elapsed;
                segmentMaxSliceNanos = Math.max(segmentMaxSliceNanos, elapsed);
                segmentOperations += processed;
                segmentWrites += writes;
                segmentMaxSliceOperations = Math.max(segmentMaxSliceOperations, processed);
            }
            if (processed > 0 && !data.advanceGenerationCursor(processed)) {
                failSegment(data, "could not persist operation cursor for " + segmentId);
                return;
            }
            if (cursor + processed == total) {
                finishSegment(data, segmentId);
            }
        } catch (RuntimeException e) {
            failSegment(data, "segment " + segmentId + " failed: " + e.getMessage());
            LOGGER.error("[publicmines] Segment {} failed while generating.", segmentId, e);
        }
    }

    private SegmentGeometry.SegmentPlan createPlan(String id) {
        if (id.startsWith("node:")) {
            String nodeId = id.substring("node:".length());
            TopoNode node = topology.node(nodeId);
            if (node != null) {
                return SegmentGeometry.node(topology, node, topology.bounds(), ARRIVAL);
            }
        } else if (id.startsWith("edge:")) {
            TopoEdge edge = edgesBySegmentId.get(id);
            if (edge != null) {
                return SegmentGeometry.edge(topology, edge, topology.bounds(), ARRIVAL);
            }
        } else if (id.equals("connector:arrival:" + topology.entranceId())) {
            return SegmentGeometry.entryConnector(topology, topology.bounds(), ARRIVAL);
        }
        throw new IllegalArgumentException("unknown segment id in saved progress: " + id);
    }

    private boolean applyOperation(ServerLevel mine, BlockPos pos,
                                   SegmentGeometry.BlockKind kind, int y) {
        BlockState replacement = blockFor(kind, pos, y);
        BlockState existing = mine.getBlockState(pos);
        if (existing.equals(replacement)) {
            return false;
        }
        if (!existing.isAir() && !isMinePaletteBlock(existing)) {
            // Preserve later content (M3+) and administrator/player blocks
            // when an interrupted operation is replayed.
            return false;
        }
        mine.setBlock(pos, replacement, Block.UPDATE_CLIENTS);
        return true;
    }

    private BlockState blockFor(SegmentGeometry.BlockKind kind, BlockPos pos, int y) {
        if (kind == SegmentGeometry.BlockKind.OPEN) {
            return Blocks.CAVE_AIR.defaultBlockState();
        }
        if (kind == SegmentGeometry.BlockKind.HAZARD_BOUNDARY) {
            return Blocks.BASALT.defaultBlockState();
        }
        if (kind == SegmentGeometry.BlockKind.HAZARD_SOURCE) {
            return Blocks.LAVA.defaultBlockState();
        }
        String region = regionForY(y);
        if ("chaos".equals(region)) {
            if (isNetherrackClusterBlock(pos)) {
                return Blocks.NETHERRACK.defaultBlockState();
            }
            int palette = Math.floorMod(blockHash(pos), 5);
            return switch (palette) {
                case 0 -> Blocks.BLACKSTONE.defaultBlockState();
                case 1, 2 -> Blocks.BASALT.defaultBlockState();
                default -> Blocks.DEEPSLATE.defaultBlockState();
            };
        }
        return y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    private String regionForY(int y) {
        String found = "upper";
        for (var region : TopologyProfiles.active().regions()) {
            if (y <= region.topY() && y >= region.bottomY()) {
                found = region.id();
            }
        }
        return found;
    }

    private boolean isNetherrackClusterBlock(BlockPos pos) {
        MineRunData data = manager.data();
        if (!data.generationNetherrackEnabled()) {
            return false;
        }
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        long hash = chunkHash(chunkX, chunkZ);
        int interval = data.generationNetherrackInterval();
        if (Math.floorMod(hash, interval) != 0) {
            return false;
        }
        var chaos = TopologyProfiles.active().regions().stream()
                .filter(region -> "chaos".equals(region.id())).findFirst().orElse(null);
        if (chaos == null) {
            return false;
        }
        int clusterHeight = PublicMineConfig.NETHERRACK_CLUSTER_MAX_BLOCKS;
        int span = chaos.topY() - chaos.bottomY() - clusterHeight + 1;
        if (span < 1) {
            return false;
        }
        int targetX = chunkX * 16 + Math.floorMod((int) (hash >>> 32), 16);
        int targetZ = chunkZ * 16 + Math.floorMod((int) (hash >>> 16), 16);
        int baseY = chaos.bottomY() + Math.floorMod((int) hash, span);
        return pos.getX() == targetX && pos.getZ() == targetZ
                && pos.getY() >= baseY && pos.getY() < baseY + clusterHeight;
    }

    private long chunkHash(int x, int z) {
        return mix(manager.data().seed() ^ ((long) x * 0x9E3779B97F4A7C15L)
                ^ ((long) z * 0xC2B2AE3D27D4EB4FL));
    }

    private long blockHash(BlockPos pos) {
        return mix(chunkHash(pos.getX(), pos.getZ()) ^ ((long) pos.getY() * 0x165667B19E3779F9L));
    }

    private static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private boolean insideOwnedVolume(ServerLevel level, BlockPos pos) {
        for (OwnedVolume volume : ownedVolumes) {
            if (volume.contains(level, pos)) {
                return true;
            }
        }
        return false;
    }

    private boolean isMinePaletteBlock(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.STONE || block == Blocks.DEEPSLATE || block == Blocks.BASALT
                || block == Blocks.BLACKSTONE || block == Blocks.NETHERRACK || block == Blocks.LAVA
                || block == Blocks.CAVE_AIR
                || block == Blocks.AIR || block == Blocks.COBBLED_DEEPSLATE;
    }

    private void finishSegment(MineRunData data, String segmentId) {
        if (!data.completeActiveGenerationSegment()) {
            failSegment(data, "could not persist completion for " + segmentId);
            return;
        }
        activePlan = null;
        if (segmentId.startsWith("connector:arrival:")) {
            data.enqueueGenerationSegment(nodeSegmentId(topology.entranceId()));
        } else if (segmentId.startsWith("edge:")) {
            TopoEdge edge = edgesBySegmentId.get(segmentId);
            if (edge != null) {
                data.enqueueGenerationSegment(nodeSegmentId(edge.a()));
                data.enqueueGenerationSegment(nodeSegmentId(edge.b()));
            }
        }
        LOGGER.info("[publicmines] Generated segment {} ({} complete, {} queued).",
                segmentId, data.completedGenerationSegments().size(), data.pendingGenerationSegments().size());
        LOGGER.info("[publicmines] Segment profile {}: {} operations, {} block writes, max {} ops in one tick, "
                        + "{} ms total / {} ms maximum slice / {} ms maximum block operation "
                        + "(budget {} operations and {} ms/tick).",
                profiledSegmentId, segmentOperations, segmentWrites, segmentMaxSliceOperations,
                String.format(Locale.ROOT, "%.2f", segmentElapsedNanos / 1_000_000.0),
                String.format(Locale.ROOT, "%.2f", segmentMaxSliceNanos / 1_000_000.0),
                String.format(Locale.ROOT, "%.2f", segmentMaxOperationNanos / 1_000_000.0),
                OPERATIONS_PER_TICK, SLICE_BUDGET_NANOS / 1_000_000L);
    }

    private void failSegment(MineRunData data, String detail) {
        manager.failGeneration(detail + "; inspect '/publicmine status' before retrying");
        LOGGER.error("[publicmines] {}", detail);
        activePlan = null;
    }

    private String generationFingerprint(TopologyPlan plan, boolean netherrackEnabled,
                                        int netherrackInterval) {
        String input = GEOMETRY_VERSION + "\n" + plan.fingerprint() + "\n"
                + "netherrack=" + netherrackEnabled + ":" + netherrackInterval;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private Map<String, TopoEdge> indexEdges(TopologyPlan plan) {
        Map<String, TopoEdge> indexed = new HashMap<>();
        for (TopoEdge edge : plan.edges()) {
            indexed.put(edgeSegmentId(edge), edge);
        }
        return Map.copyOf(indexed);
    }

    private Map<Long, List<TopoNode>> indexNodes(TopologyPlan plan) {
        Map<Long, List<TopoNode>> indexed = new HashMap<>();
        for (TopoNode node : plan.nodes()) {
            int chunkX = node.pos().x() >> 4;
            int chunkZ = node.pos().z() >> 4;
            indexed.computeIfAbsent(chunkKey(chunkX, chunkZ), ignored -> new ArrayList<>()).add(node);
        }
        Map<Long, List<TopoNode>> frozen = new HashMap<>();
        for (Map.Entry<Long, List<TopoNode>> entry : indexed.entrySet()) {
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(frozen);
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFF_FFFFL);
    }

    private static String nodeSegmentId(String nodeId) {
        return "node:" + nodeId;
    }

    private static String edgeSegmentId(TopoEdge edge) {
        return "edge:" + edge.a() + ":" + edge.b();
    }
}
