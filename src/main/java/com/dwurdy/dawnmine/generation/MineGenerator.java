package com.dwurdy.dawnmine.generation;

import com.mojang.logging.LogUtils;
import com.dwurdy.dawnmine.encounter.MineEncounterManager;
import com.dwurdy.dawnmine.config.DawnmineConfig;
import com.dwurdy.dawnmine.run.MineManager;
import com.dwurdy.dawnmine.run.MineRunData;
import com.dwurdy.dawnmine.run.OwnedVolume;
import com.dwurdy.dawnmine.run.RunStatus;
import com.dwurdy.dawnmine.topology.MineTopology;
import com.dwurdy.dawnmine.topology.TopoEdge;
import com.dwurdy.dawnmine.topology.TopoNode;
import com.dwurdy.dawnmine.topology.TopoPos;
import com.dwurdy.dawnmine.topology.TopologyPlan;
import com.dwurdy.dawnmine.topology.TopologyProfiles;
import com.dwurdy.dawnmine.topology.TopologyReport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Prepares the whole shared mine before anyone is admitted. All structural
 * segments are queued deterministically first, then every dressing pass,
 * then every ore pass, so no later structural write can bury a support or a
 * vein. Chunk work is bounded asynchronous requests in a small window - the
 * scheduler never blocks the server thread on the whole mine and players
 * never see the mine being carved.
 */
public final class MineGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();
    // Block updates can trigger lighting and neighboring-chunk work. Keep both
    // a strict operation ceiling and a soft elapsed-time ceiling per tick.
    private static final int OPERATIONS_PER_TICK = 4096;
    private static final long SLICE_BUDGET_NANOS = 5_000_000L;
    private static final int PLAN_CHECK_TICKS = 200;
    /** Bounded window of outstanding asynchronous chunk requests. */
    private static final int CHUNK_REQUEST_WINDOW = 9;
    /** How often generated nodes are checked for approaching players. */
    private static final int ENCOUNTER_SCAN_TICKS = 30;
    /** Outstanding region-pregen futures. getChunkFuture pumps main-thread
     * chunk work while it waits, so issue count per tick must stay small -
     * issuing many at once stalled the tick past the watchdog timeout. */
    private static final int PREGEN_WINDOW = 32;
    /** New chunk futures issued per tick; mirrors the proven segment
     * request window (9) so issuing never stalls a tick. */
    private static final int PREGEN_ISSUE_PER_TICK = 8;
    /** Columns scanned per tick while looking for ungenerated region chunks. */
    private static final int PREGEN_SCAN_PER_TICK = 256;
    /** How often mine occupants are scanned for lookahead pregen. */
    private static final int LOOKAHEAD_TICKS = 20;
    /** Chunk radius silently pregenerated ahead of each mine occupant. */
    private static final int LOOKAHEAD_RADIUS = 10;
    private static final double ENCOUNTER_APPROACH_DISTANCE_SQ = 56.0 * 56.0;
    private static final TopoPos ARRIVAL = new TopoPos(
            MineManager.ARRIVAL.getX(), MineManager.ARRIVAL.getY(), MineManager.ARRIVAL.getZ());
    private static final String GEOMETRY_VERSION = "dawnmine-segment-geometry-v13-anchor-band-reroll";

    private static MineGenerator instance;

    private final MinecraftServer server;
    private final MineManager manager;
    private TopologyPlan topology;
    private String loadedRunId = "";
    private String loadedTopologyFingerprint = "";
    private List<String> expectedSegments = List.of();
    private Set<String> placedLandmarkNodeIds = Set.of();
    /** Catalog snapshots pinned when the run's topology loads, so every
     * segment of this run plans against the exact content the generation
     * fingerprint was receipted under - a datapack reload mid-carve can
     * never interleave foreign blocks between refresh checks. */
    private OreCatalog.OreCatalogSnapshot pinnedOres;
    private Map<String, MotifCatalog.Motif> pinnedMotifs = Map.of();
    private Map<String, LandmarkCatalog.Definition> pinnedLandmarks = Map.of();
    private long lastPlanCheck = Long.MIN_VALUE;
    private long lastEncounterScan = Long.MIN_VALUE;
    private Map<String, TopoEdge> edgesBySegmentId = Map.of();
    private Set<String> jackpotNodeIds = null;
    private Map<Long, List<TopoNode>> nodesByChunk = Map.of();
    private List<OwnedVolume> ownedVolumes = List.of();
    /** Outstanding chunk futures, keyed by packed chunk coordinate. */
    private final Map<Long, CompletableFuture<?>> chunkRequests = new LinkedHashMap<>();
    /** Region tickets this generator owns; released on every terminal path. */
    private final Set<Long> heldTickets = new HashSet<>();
    /** Whole-region pregen: every shell column is generated before entry
     * opens, so no player ever watches terrain carve itself. The backlog is
     * a scan cursor over the persisted shell bounds; futures are bounded
     * async loads that never touch the server thread. */
    private final Map<Long, CompletableFuture<?>> pregenRequests = new LinkedHashMap<>();
    /** Every column ever queued this run; dedupes backlog and lookahead. */
    private final Set<Long> pregenPlanned = new HashSet<>();
    /** Lookahead columns offered by player proximity; drained before the
     * square scan so approaching players get their neighborhood first. */
    private final java.util.ArrayDeque<Long> pregenPriority = new java.util.ArrayDeque<>();
    private int pregenScanX;
    private int pregenScanZ;
    private int pregenMinX;
    private int pregenMinZ;
    private int pregenMaxX;
    private int pregenMaxZ;
    private boolean pregenComplete;
    private boolean pregenCompletionLogged;
    private long lastLookaheadScan = Long.MIN_VALUE;
    private static final TicketType<ChunkPos> GENERATION_TICKET = TicketType.create(
            "dawnmine_preparation", Comparator.comparingLong(ChunkPos::toLong));
    private SegmentGeometry.SegmentPlan activePlan;
    private String profiledSegmentId = "";
    private long segmentElapsedNanos;
    private long segmentMaxSliceNanos;
    private int segmentOperations;
    private int segmentWrites;
    private int segmentMaxSliceOperations;
    private long segmentMaxOperationNanos;
    private long prepStartedNanos;
    private boolean prepCompletionLogged;

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
        jackpotNodeIds = null;
        activePlan = null;
        chunkRequests.clear();
        prepStartedNanos = System.nanoTime();
        prepCompletionLogged = false;
        MineRunData data = manager.data();
        data.restartActiveGenerationSegmentForReplay();
        if (refreshPlan(true)) {
            restoreCompletedCheckpointStations(data);
            ensurePreparedQueued(data);
        }
    }

    /** Backfills shared stations for runs generated before the registry existed. */
    private void restoreCompletedCheckpointStations(MineRunData data) {
        for (String segmentId : data.completedGenerationSegments()) {
            if (!segmentId.startsWith("node:")) continue;
            TopoNode node = topology.node(segmentId.substring("node:".length()));
            if (node != null && node.has(com.dwurdy.dawnmine.topology.NodeFeature.CHECKPOINT)) {
                registerCheckpointStation(node);
            }
        }
    }

    /** ServerTickEvent.Post handler. Mutations are capped by operation count. */
    public synchronized void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer() != server) {
            return;
        }
        MineRunData data = manager.data();
        // Preparation (and everything else the generator does) stops dead
        // while a reset transaction owns the mine - not just on status.
        if (data.status() != RunStatus.READY || data.resetInProgress()) {
            releaseChunkRequests();
            return;
        }

        long tick = server.getTickCount();
        if (topology == null || !loadedRunId.equals(data.runId())
                || tick - lastPlanCheck >= PLAN_CHECK_TICKS) {
            if (!refreshPlan(false)) {
                return;
            }
        }
        ensurePreparedQueued(data);
        processOneSlice(data, manager.mineLevel());
        drainRegionPregen(manager.mineLevel());
        if (tick - lastLookaheadScan >= LOOKAHEAD_TICKS) {
            lastLookaheadScan = tick;
            scanPlayerLookahead(manager.mineLevel());
        }
        if (tick - lastEncounterScan >= ENCOUNTER_SCAN_TICKS) {
            lastEncounterScan = tick;
            spawnApproachingEncounters(data, manager.mineLevel());
        }
    }

    private boolean refreshPlan(boolean startup) {
        MineRunData data = manager.data();
        ServerLevel mine = manager.mineLevel();
        if (data.status() != RunStatus.READY || mine == null) {
            return false;
        }
        String shellError = MineShellGeometry.configurationError(mine,
                DawnmineConfig.mineRegionRadiusChunks());
        if (shellError.isEmpty()) {
            shellError = MineShellGeometry.persistedBoundsError(data,
                    DawnmineConfig.mineRegionRadiusChunks());
        }
        if (!shellError.isEmpty()) {
            manager.failGeneration("mine boundary validation failed: " + shellError);
            LOGGER.error("[dawnmine] Refusing generation because boundary validation failed: {}", shellError);
            return false;
        }
        OreCatalog.Completeness oreCompleteness = OreCatalog.active().completeness(mine);
        if (!oreCompleteness.complete()) {
            manager.failGeneration("ore catalog is incomplete: " + OreCatalog.describe(oreCompleteness));
            LOGGER.error("[dawnmine] Refusing generation because the ore catalog is incomplete: {}",
                    OreCatalog.describe(oreCompleteness));
            return false;
        }
        try {
            TopologyPlan planned = MineTopology.plan(data, mine);
            TopologyReport report = MineTopology.validate(planned);
            if (!report.ok()) {
                manager.failGeneration("topology validation failed: " + report.describe());
                LOGGER.error("[dawnmine] Refusing generation for invalid topology: {}", report.describe());
                return false;
            }
            if (!planned.fingerprint().equals(data.topologyFingerprint())) {
                manager.failGeneration("active topology differs from the saved run receipt");
                LOGGER.error("[dawnmine] Refusing generation because the active topology fingerprint differs from the run receipt.");
                return false;
            }

            boolean netherrackEnabled = data.generationPlanFingerprint().isEmpty()
                    ? DawnmineConfig.netherrackEnabled() : data.generationNetherrackEnabled();
            int netherrackInterval = data.generationPlanFingerprint().isEmpty()
                    ? DawnmineConfig.netherrackColumnInterval() : data.generationNetherrackInterval();
            String generationFingerprint = generationFingerprint(planned,
                    netherrackEnabled, netherrackInterval);
            if (!data.beginGeneration(generationFingerprint, netherrackEnabled, netherrackInterval)) {
                manager.failGeneration("saved generation plan fingerprint does not match the active geometry settings; start a new run");
                LOGGER.error("[dawnmine] Refusing generation because the saved geometry plan fingerprint has drifted.");
                return false;
            }
            if (!loadedRunId.equals(data.runId())) {
                prepStartedNanos = System.nanoTime();
                prepCompletionLogged = false;
            }
            if (!loadedTopologyFingerprint.equals(planned.fingerprint()) || !loadedRunId.equals(data.runId())) {
                topology = planned;
                jackpotNodeIds = null;
                loadedRunId = data.runId();
                loadedTopologyFingerprint = planned.fingerprint();
                activePlan = null;
                edgesBySegmentId = indexEdges(planned);
                nodesByChunk = indexNodes(planned);
                ownedVolumes = List.copyOf(data.ownedVolumes());
                pinnedOres = OreCatalog.active();
                pinnedMotifs = MotifCatalog.active();
                pinnedLandmarks = LandmarkCatalog.definitions();
                placedLandmarkNodeIds = Set.copyOf(
                        PassageNetwork.of(planned, ARRIVAL, pinnedLandmarks).landmarks()
                                .placements().keySet());
                expectedSegments = PreparationPlan.expectedSegments(
                        planned, placedLandmarkNodeIds);
                releaseChunkRequests();
            }
            lastPlanCheck = server.getTickCount();
            if (startup) {
                data.restartActiveGenerationSegmentForReplay();
            }
            return true;
        } catch (RuntimeException e) {
            manager.failGeneration("could not validate the saved plan: " + e.getMessage());
            LOGGER.error("[dawnmine] Refusing mine generation after plan validation error.", e);
            return false;
        }
    }

    /** Enqueues the whole deterministic preparation set once; the persisted
     * queue deduplicates, so a restart re-adds only what is missing. */
    private void ensurePreparedQueued(MineRunData data) {
        if (topology == null || expectedSegments.isEmpty()) {
            return;
        }
        for (String segmentId : expectedSegments) {
            data.enqueueGenerationSegment(segmentId);
        }
    }

    /**
     * Readiness surface shared by admission, checkpoint travel, login
     * recovery, and status: complete means every expected segment for the
     * current run/fingerprint is done, not merely that the run is READY.
     */
    public synchronized PreparationPlan.Progress preparation() {
        MineRunData data = manager.data();
        if (data.status() != RunStatus.READY || data.resetInProgress()
                || data.generationPlanFingerprint().isEmpty()) {
            return new PreparationPlan.Progress(0, 0,
                    data.pendingGenerationSegments().size(),
                    data.activeGenerationSegment());
        }
        if (topology == null || expectedSegments.isEmpty()
                || !loadedRunId.equals(data.runId())) {
            if (!ensureTopologyForReadiness(data)) {
                return new PreparationPlan.Progress(0, 0,
                        data.pendingGenerationSegments().size(),
                        data.activeGenerationSegment());
            }
        }
        return PreparationPlan.progress(topology, placedLandmarkNodeIds,
                new HashSet<>(data.completedGenerationSegments()),
                data.pendingGenerationSegments().size(),
                data.activeGenerationSegment());
    }

    public synchronized boolean preparationComplete() {
        return preparation().complete() && pregenComplete;
    }

    /** Readiness is checked outside the tick loop too (entry gating), so the
     * plan may need to be computed before the first generator tick. */
    private boolean ensureTopologyForReadiness(MineRunData data) {
        if (data.status() != RunStatus.READY || data.topologyFingerprint().isEmpty()) {
            return false;
        }
        ServerLevel mine = manager.mineLevel();
        if (mine == null) {
            return false;
        }
        try {
            TopologyPlan planned = MineTopology.plan(data, mine);
            if (!planned.fingerprint().equals(data.topologyFingerprint())) {
                return false;
            }
            // The readiness path must not pin a catalog epoch the receipt
            // never authorized: when the saved generation fingerprint has
            // drifted (datapack reload since the run was receipted),
            // refreshPlan is the authoritative fail path - refuse to
            // re-pin here instead of reporting progress under a foreign
            // epoch for a few hundred ticks.
            String receipt = data.generationPlanFingerprint();
            if (!receipt.isEmpty() && !receipt.equals(generationFingerprint(
                    planned, data.generationNetherrackEnabled(),
                    data.generationNetherrackInterval()))) {
                return false;
            }
            topology = planned;
            jackpotNodeIds = null;
            loadedRunId = data.runId();
            loadedTopologyFingerprint = planned.fingerprint();
            edgesBySegmentId = indexEdges(planned);
            nodesByChunk = indexNodes(planned);
            ownedVolumes = List.copyOf(data.ownedVolumes());
            pinnedOres = OreCatalog.active();
            pinnedMotifs = MotifCatalog.active();
            pinnedLandmarks = LandmarkCatalog.definitions();
            placedLandmarkNodeIds = Set.copyOf(
                    PassageNetwork.of(planned, ARRIVAL, pinnedLandmarks).landmarks()
                            .placements().keySet());
            expectedSegments = PreparationPlan.expectedSegments(
                    planned, placedLandmarkNodeIds);
            resetRegionPregen(data);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void processOneSlice(MineRunData data, ServerLevel mine) {
        if (mine == null) {
            return;
        }
        String segmentId = data.beginNextGenerationSegment();
        if (segmentId.isEmpty()) {
            activePlan = null;
            // Entry must not open (and optional structures must not place)
            // until the whole region is silently generated behind the scenes.
            if (pregenComplete) {
                notePreparationComplete(data);
            }
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

            if (!requestUpcomingChunks(mine, activePlan, cursor)) {
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
                if (!ownsReserveCell(segmentId, position)
                        && SegmentGeometry.isArrivalKeepOut(position, ARRIVAL)) {
                    failSegment(data, "segment " + segmentId + " overlapped a structure reserve at "
                            + MineManager.describePos(blockPos));
                    return;
                }
                if (!mine.getChunkSource().hasChunk(blockPos.getX() >> 4, blockPos.getZ() >> 4)) {
                    break;
                }
                if (operation.kind() == SegmentGeometry.BlockKind.ORE
                        && !oreNeighboursLoaded(mine, blockPos)) {
                    break;
                }
                long operationStarted = System.nanoTime();
                if (applyOperation(mine, blockPos, operation, position.y())) {
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
            LOGGER.error("[dawnmine] Segment {} failed while generating.", segmentId, e);
        }
    }

    /** Issues bounded asynchronous chunk requests for the next operations of
     * the active segment (at most {@link #CHUNK_REQUEST_WINDOW} outstanding),
     * so the writer never stalls the server thread waiting on world load. */
    private boolean requestUpcomingChunks(ServerLevel mine, SegmentGeometry.SegmentPlan plan, int cursor) {
        for (Map.Entry<Long, CompletableFuture<?>> entry : chunkRequests.entrySet()) {
            CompletableFuture<?> future = entry.getValue();
            if (future.isCompletedExceptionally() || future.isCancelled()) {
                failSegment(manager.data(), "chunk " + entry.getKey()
                        + " load request failed during preparation");
                return false;
            }
            if (future.isDone() && future.getNow(null) instanceof ChunkResult<?> result
                    && !result.isSuccess()) {
                failSegment(manager.data(), "chunk " + entry.getKey()
                        + " unavailable during preparation: " + result.getError());
                return false;
            }
        }
        Set<Long> needed = new java.util.LinkedHashSet<>();
        List<SegmentGeometry.BlockOperation> operations = plan.operations();
        SegmentGeometry.BlockOperation first = operations.get(cursor);
        if (first.kind() == SegmentGeometry.BlockKind.ORE) {
            TopoPos pos = first.position();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    needed.add(ChunkPos.asLong((pos.x() >> 4) + dx, (pos.z() >> 4) + dz));
                }
            }
        } else {
            for (int i = cursor; i < operations.size() && needed.size() < CHUNK_REQUEST_WINDOW; i++) {
                TopoPos pos = operations.get(i).position();
                needed.add(ChunkPos.asLong(pos.x() >> 4, pos.z() >> 4));
            }
        }
        chunkRequests.entrySet().removeIf(entry ->
                !needed.contains(entry.getKey()) || entry.getValue().isDone());
        heldTickets.removeIf(packed -> {
            if (needed.contains(packed)) {
                return false;
            }
            releaseTicket(mine, packed);
            return true;
        });
        for (Long packed : needed) {
            int cx = ChunkPos.getX(packed);
            int cz = ChunkPos.getZ(packed);
            ChunkPos chunkPos = new ChunkPos(cx, cz);
            if (!heldTickets.contains(packed)) {
                // Own the load explicitly so it can be released deterministically.
                mine.getChunkSource().addRegionTicket(GENERATION_TICKET, chunkPos, 0, chunkPos);
                heldTickets.add(packed);
            }
            if (mine.getChunkSource().hasChunk(cx, cz) || chunkRequests.containsKey(packed)) {
                continue;
            }
            CompletableFuture<?> future = mine.getChunkSource()
                    .getChunkFuture(cx, cz, ChunkStatus.FULL, true);
            future.whenComplete((result, error) -> {
                if (error != null) {
                    LOGGER.error("[dawnmine] chunk ({},{}) load failed during preparation: {}",
                            cx, cz, error.getMessage());
                }
            });
            chunkRequests.put(packed, future);
        }
        return true;
    }

    private static boolean oreNeighboursLoaded(ServerLevel mine, BlockPos pos) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!mine.getChunkSource().hasChunk(cx + dx, cz + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void releaseTicket(ServerLevel mine, long packed) {
        ChunkPos pos = new ChunkPos(ChunkPos.getX(packed), ChunkPos.getZ(packed));
        mine.getChunkSource().removeRegionTicket(GENERATION_TICKET, pos, 0, pos);
    }

    /** Initializes the whole-region pregen backlog for the active run. The
     * scan cursor walks the persisted shell square (or the configured
     * radius when bounds were never persisted), one column at a time. */
    private void resetRegionPregen(MineRunData data) {
        pregenRequests.clear();
        pregenPriority.clear();
        pregenPlanned.clear();
        pregenComplete = false;
        pregenCompletionLogged = false;
        int radius = DawnmineConfig.mineRegionRadiusChunks();
        int sizeX = data.boundMaxChunkX() - data.boundMinChunkX() + 1;
        int sizeZ = data.boundMaxChunkZ() - data.boundMinChunkZ() + 1;
        // Persisted bounds from older saves can be degenerate (e.g. a 1x1
        // default); only trust bounds that plausibly cover the shell.
        if (sizeX >= radius && sizeZ >= radius) {
            pregenMinX = data.boundMinChunkX();
            pregenMinZ = data.boundMinChunkZ();
            pregenMaxX = data.boundMaxChunkX();
            pregenMaxZ = data.boundMaxChunkZ();
        } else {
            pregenMinX = -radius;
            pregenMinZ = -radius;
            pregenMaxX = radius;
            pregenMaxZ = radius;
        }
        pregenScanX = pregenMinX;
        pregenScanZ = pregenMinZ;
    }

    /** Issues bounded async FULL-chunk loads for every shell column not yet
     * generated. Work happens on the chunk executor; the tick only maintains
     * the window and polls completions, so the server never stalls. */
    private void drainRegionPregen(ServerLevel mine) {
        if (pregenComplete || mine == null) {
            return;
        }
        pregenRequests.entrySet().removeIf(entry -> {
            CompletableFuture<?> future = entry.getValue();
            if (future.isCompletedExceptionally() || future.isCancelled()) {
                LOGGER.warn("[dawnmine] Region pregen chunk {} failed; skipping column.",
                        new ChunkPos(entry.getKey()));
                return true;
            }
            return future.isDone()
                    || mine.getChunkSource().hasChunk(
                            ChunkPos.getX(entry.getKey()), ChunkPos.getZ(entry.getKey()));
        });
        int issued = 0;
        while (pregenRequests.size() < PREGEN_WINDOW && issued < PREGEN_ISSUE_PER_TICK) {
            Long packed = pregenPriority.pollFirst();
            if (packed == null) {
                packed = nextRegionColumn(mine);
            }
            if (packed == null) {
                break;
            }
            issued++;
            int cx = ChunkPos.getX(packed);
            int cz = ChunkPos.getZ(packed);
            CompletableFuture<?> future = mine.getChunkSource()
                    .getChunkFuture(cx, cz, ChunkStatus.FULL, true);
            long column = packed;
            future.whenComplete((result, error) -> {
                if (error != null) {
                    LOGGER.error("[dawnmine] Region pregen chunk ({},{}) failed: {}",
                            cx, cz, error.getMessage());
                }
            });
            pregenRequests.put(column, future);
        }
        if (pregenScanX > pregenMaxX && pregenPriority.isEmpty() && pregenRequests.isEmpty()) {
            pregenComplete = true;
            if (!pregenCompletionLogged) {
                pregenCompletionLogged = true;
                LOGGER.info("[dawnmine] Region pregen complete for run {}: all {} shell columns generated.",
                        manager.data().runId(), pregenPlanned.size());
            }
        }
    }

    /** Next ungenerated column in the row-major shell scan, or null when the
     * scan is exhausted (or this tick's scan budget is spent). */
    private Long nextRegionColumn(ServerLevel mine) {
        int radius = DawnmineConfig.mineRegionRadiusChunks();
        int scanned = 0;
        while (pregenScanX <= pregenMaxX && scanned++ < PREGEN_SCAN_PER_TICK) {
            int cx = pregenScanX;
            int cz = pregenScanZ;
            if (++pregenScanZ > pregenMaxZ) {
                pregenScanZ = pregenMinZ;
                pregenScanX++;
            }
            if (!MineShellGeometry.inside(cx, cz, radius)) {
                continue;
            }
            long packed = ChunkPos.asLong(cx, cz);
            if (pregenPlanned.contains(packed)) {
                continue;
            }
            pregenPlanned.add(packed);
            if (mine.getChunkSource().hasChunk(cx, cz)) {
                continue;
            }
            return packed;
        }
        return null;
    }

    /** Silent lookahead: when an occupant is inside the mine, queue any
     * ungenerated shell columns in a radius ahead of them at the front of
     * the pregen queue - the surrounding area materializes before they get
     * there, without a single tick of synchronous generation. */
    private void scanPlayerLookahead(ServerLevel mine) {
        if (pregenComplete || mine == null) {
            return;
        }
        int radius = DawnmineConfig.mineRegionRadiusChunks();
        int offered = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.serverLevel() != mine) {
                continue;
            }
            int pcx = player.blockPosition().getX() >> 4;
            int pcz = player.blockPosition().getZ() >> 4;
            for (int dz = -LOOKAHEAD_RADIUS; dz <= LOOKAHEAD_RADIUS && offered < 32; dz++) {
                for (int dx = -LOOKAHEAD_RADIUS; dx <= LOOKAHEAD_RADIUS && offered < 32; dx++) {
                    int cx = pcx + dx;
                    int cz = pcz + dz;
                    long packed = ChunkPos.asLong(cx, cz);
                    if (pregenPlanned.contains(packed)
                            || !MineShellGeometry.inside(cx, cz, radius)
                            || mine.getChunkSource().hasChunk(cx, cz)) {
                        continue;
                    }
                    pregenPlanned.add(packed);
                    pregenPriority.offerLast(packed);
                    offered++;
                }
            }
        }
    }

    /** Drops all outstanding chunk requests: run change, reset, failure,
     * shutdown, and segment completion all funnel here. */
    private void releaseChunkRequests() {
        chunkRequests.clear();
        resetRegionPregen(manager.data());
        ServerLevel mine = manager.mineLevel();
        if (mine != null) {
            for (Long packed : List.copyOf(heldTickets)) {
                releaseTicket(mine, packed);
            }
        }
        heldTickets.clear();
        activePlan = null;
    }

    /** Count of generator-owned region tickets; exposed for tests/status. */
    public synchronized int heldTicketCount() {
        return heldTickets.size();
    }

    /** ServerStoppingEvent hook: drop all owned tickets before save. */
    public synchronized void onServerStopping() {
        releaseChunkRequests();
    }

    private void notePreparationComplete(MineRunData data) {
        if (topology == null || expectedSegments.isEmpty()) {
            return;
        }
        if (!PreparationPlan.progress(topology, placedLandmarkNodeIds,
                new HashSet<>(data.completedGenerationSegments()),
                data.pendingGenerationSegments().size(), "").complete()) {
            return;
        }
        if (!prepCompletionLogged) {
            prepCompletionLogged = true;
            long elapsedMs = (System.nanoTime() - prepStartedNanos) / 1_000_000L;
            LOGGER.info("[dawnmine] Run {} preparation complete: {} segments generated in {} ms.",
                    data.runId(), expectedSegments.size(), elapsedMs);
            ServerLevel mine = manager.mineLevel();
            try {
                int shafts = mine != null
                        ? MineMineshaftPlacer.place(server, mine, data, topology) : 0;
                LOGGER.info("[dawnmine] Run {} mineshaft placement finished: {} sites placed.",
                        data.runId(), shafts);
            } catch (RuntimeException e) {
                LOGGER.error("[dawnmine] Mineshaft placement failed for run {} (non-fatal).",
                        data.runId(), e);
            }
        }
    }

    /** Encounter sites only spawn when a player actually approaches their
     * room; whole-run preparation must not populate the mine in advance. */
    private void spawnApproachingEncounters(MineRunData data, ServerLevel mine) {
        if (mine == null || topology == null) {
            return;
        }
        Set<String> completed = new HashSet<>(data.completedGenerationSegments());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.serverLevel() != mine) {
                continue;
            }
            int chunkX = player.blockPosition().getX() >> 4;
            int chunkZ = player.blockPosition().getZ() >> 4;
            for (int dz = -4; dz <= 4; dz++) {
                for (int dx = -4; dx <= 4; dx++) {
                    List<TopoNode> candidates = nodesByChunk.get(chunkKey(chunkX + dx, chunkZ + dz));
                    if (candidates == null) {
                        continue;
                    }
                    for (TopoNode node : candidates) {
                        if (!completed.contains("node:" + node.id())
                                || data.hasClaimedEncounterSite("node:" + node.id())) {
                            continue;
                        }
                        if (player.distanceToSqr(node.pos().x() + 0.5, node.pos().y(),
                                node.pos().z() + 0.5) > ENCOUNTER_APPROACH_DISTANCE_SQ) {
                            continue;
                        }
                        MineEncounterManager.onNodeGenerated(server, physicalNode(node));
                    }
                }
            }
        }
    }

    /** Whether {@code segmentId} owns the reserve cell {@code pos} writes
     * land on - the hub owns the hub reserve and each {@code landmark:}
     * segment owns its own placed footprint. Every other segment is a
     * foreign writer and must respect the keepout. */
    private boolean ownsReserveCell(String segmentId, TopoPos pos) {
        if (segmentId.equals("hub")) {
            return true;
        }
        if (segmentId.startsWith(StaticLandmarkBuilder.SEGMENT_PREFIX)) {
            LandmarkLayout.Placement placement = PassageNetwork.of(topology, ARRIVAL, pinnedLandmarks)
                    .landmarks().placement(StaticLandmarkBuilder.nodeId(segmentId));
            return placement != null && placement.insideReserve(pos);
        }
        return false;
    }

    private SegmentGeometry.SegmentPlan createPlan(String id) {
        if (PreparationPlan.isDressSegment(id)) {
            return SegmentGeometry.dress(topology, PreparationPlan.structuralIdFor(id),
                    topology.bounds(), ARRIVAL);
        }
        if (PreparationPlan.isMotifSegment(id)) {
            return createMotifPlan(id);
        }
        if (PreparationPlan.isOreSegment(id)) {
            return createOrePlan(id);
        }
        if (id.equals("hub")) {
            return SegmentGeometry.hub(topology, topology.bounds(), ARRIVAL);
        }
        if (id.startsWith(StaticLandmarkBuilder.SEGMENT_PREFIX)) {
            LandmarkLayout.Placement placement = PassageNetwork.of(topology, ARRIVAL, pinnedLandmarks)
                    .landmarks().placement(StaticLandmarkBuilder.nodeId(id));
            if (placement == null) {
                throw new IllegalStateException("landmark segment " + id
                        + " has no placement in this topology");
            }
            return StaticLandmarkBuilder.plan(placement, topology.bounds());
        }
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

    /** Ore pass: deterministic vein ops aimed at wall rock next to carved
     * air. Only the write-time air-adjacency check decides what actually
     * becomes ore, so merged carves cannot bury veins. */
    private SegmentGeometry.SegmentPlan createOrePlan(String oreSegmentId) {
        String structuralId = PreparationPlan.structuralIdFor(oreSegmentId);
        SegmentGeometry.SegmentPlan structural = createStructuralPlan(structuralId);
        List<SegmentGeometry.SegmentPlan> plans = new ArrayList<>(2);
        plans.add(structural);
        plans.add(SegmentGeometry.dress(topology, structuralId, topology.bounds(), ARRIVAL));
        // The motif pass may have embedded blocks (including ore_slot
        // veins) into candidate wall rock; treat them as occupied so a
        // catalog vein never overwrites a motif cell.
        plans.add(createMotifPlan(PreparationPlan.motifSegmentId(structuralId)));
        PassageNetwork network = PassageNetwork.of(topology, ARRIVAL, pinnedLandmarks);
        Set<TopoPos> pads = new HashSet<>();
        for (TopoNode node : topology.nodes()) {
            if (node.has(com.dwurdy.dawnmine.topology.NodeFeature.CHECKPOINT)) {
                TopoPos center = network.center(node.id());
                if (center == null) {
                    continue;
                }
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        for (int dy = -1; dy <= 2; dy++) {
                            pads.add(center.offset(dx, dy, dz));
                        }
                    }
                }
            }
        }
        LandmarkLayout landmarks = network.landmarks();
        return OrePlacementPlanner.planOreSegment(oreSegmentId, plans, pinnedOres,
                pos -> OreCatalog.Tier.parse(regionForY(pos.y())),
                veinTargetFor(structuralId), manager.data().seed(), topology.bounds(),
                pos -> SegmentGeometry.isArrivalKeepOut(pos, ARRIVAL)
                        || com.dwurdy.dawnmine.topology.HubLayout.isReserved(pos)
                        || landmarks.isReserved(pos)
                        || pads.contains(pos));
    }

    /** Motif pass: deterministic parametric decorations planned off the
     * structural + dress plans, reusing the ore pass's protection rules. */
    private SegmentGeometry.SegmentPlan createMotifPlan(String motifSegmentId) {
        return MotifPlanner.planSegment(topology,
                PreparationPlan.structuralIdFor(motifSegmentId), ARRIVAL,
                pinnedMotifs.values(), pinnedOres, pinnedLandmarks);
    }

    /** Test seam: build the structural plan for a segment id without a
     * live generator instance. Dress and ore segments are not covered -
     * GameTests that need them call the segment builders directly. */
    public static SegmentGeometry.SegmentPlan createPlanForTest(TopologyPlan topology,
                                                              String id, TopoPos arrival) {
        if (id.equals("hub")) {
            return SegmentGeometry.hub(topology, topology.bounds(), arrival);
        }
        if (id.startsWith(StaticLandmarkBuilder.SEGMENT_PREFIX)) {
            LandmarkLayout.Placement placement = PassageNetwork.of(topology, arrival)
                    .landmarks().placement(StaticLandmarkBuilder.nodeId(id));
            return placement == null ? null
                    : StaticLandmarkBuilder.plan(placement, topology.bounds());
        }
        if (id.startsWith("node:")) {
            TopoNode node = topology.node(id.substring("node:".length()));
            return node == null ? null
                    : SegmentGeometry.node(topology, node, topology.bounds(), arrival);
        }
        if (id.startsWith("edge:")) {
            for (TopoEdge edge : topology.edges()) {
                if (SegmentGeometry.edgeSegmentId(edge).equals(id)) {
                    return SegmentGeometry.edge(topology, edge, topology.bounds(), arrival);
                }
            }
            return null;
        }
        if (id.equals("connector:arrival:" + topology.entranceId())) {
            return SegmentGeometry.entryConnector(topology, topology.bounds(), arrival);
        }
        return null;
    }

    private SegmentGeometry.SegmentPlan createStructuralPlan(String structuralId) {
        if (structuralId.startsWith("node:")) {
            TopoNode node = topology.node(structuralId.substring("node:".length()));
            if (node == null) {
                throw new IllegalArgumentException("unknown node in ore segment: " + structuralId);
            }
            return SegmentGeometry.node(topology, node, topology.bounds(), ARRIVAL);
        }
        if (structuralId.startsWith("edge:")) {
            TopoEdge edge = edgesBySegmentId.get(structuralId);
            if (edge == null) {
                throw new IllegalArgumentException("unknown edge in ore segment: " + structuralId);
            }
            return SegmentGeometry.edge(topology, edge, topology.bounds(), ARRIVAL);
        }
        if (structuralId.equals("connector:arrival:" + topology.entranceId())) {
            return SegmentGeometry.entryConnector(topology, topology.bounds(), ARRIVAL);
        }
        throw new IllegalArgumentException("unknown segment id in saved progress: " + structuralId);
    }

    private int veinTargetFor(String structuralId) {
        if (structuralId.startsWith("node:")) {
            TopoNode node = topology.node(structuralId.substring("node:".length()));
            if (node != null && jackpotNodeIds().contains(node.id())) {
                return OrePlacementPlanner.jackpotVeinTarget(
                        OrePlacementPlanner.RESOURCE_FIELD_VEINS);
            }
            if (node != null && node.has(com.dwurdy.dawnmine.topology.NodeFeature.RESOURCE_FIELD)) {
                return OrePlacementPlanner.RESOURCE_FIELD_VEINS;
            }
            return OrePlacementPlanner.ORDINARY_VEINS;
        }
        if (structuralId.startsWith("edge:")) {
            TopoEdge edge = edgesBySegmentId.get(structuralId);
            if (edge == null) {
                return OrePlacementPlanner.ORDINARY_VEINS;
            }
            return OrePlacementPlanner.passageVeinTarget(
                    SegmentGeometry.passageCenterline(topology, edge).size());
        }
        // Arrival connector: the first descent must show ore too.
        TopoNode entrance = topology.node(topology.entranceId());
        if (entrance != null) {
            List<TopoPos> path = SegmentGeometry.windingPath(
                    ARRIVAL.offset(3, 0, 0), entrance.pos(),
                    topology.seed() ^ stableHash(structuralId));
            return OrePlacementPlanner.passageVeinTarget(path.size());
        }
        return OrePlacementPlanner.ORDINARY_VEINS;
    }

    private Set<String> jackpotNodeIds() {
        if (jackpotNodeIds == null) {
            jackpotNodeIds = OrePlacementPlanner.jackpotNodeIds(
                    topology.nodes(), topology.seed());
        }
        return jackpotNodeIds;
    }

    private boolean applyOperation(ServerLevel mine, BlockPos pos,
                                   SegmentGeometry.BlockOperation operation, int y) {
        BlockState replacement = blockFor(operation, pos, y);
        BlockState existing = mine.getBlockState(pos);
        if (existing.equals(replacement)) {
            if (operation.kind() == SegmentGeometry.BlockKind.ORE) {
                recordOreOrigin(mine, pos, replacement, operation);
            }
            return false;
        }
        if (!existing.isAir() && !isMinePaletteBlock(existing)) {
            // Preserve later content and administrator/player blocks
            // when an interrupted operation is replayed.
            return false;
        }
        if (operation.kind() == SegmentGeometry.BlockKind.ORE) {
            // Ore only ever lands on exposed natural wall rock: the cell
            // must still be natural rock and touch real air. Intersecting
            // carves therefore leave veins visible instead of buried.
            if (!isNaturalHostRock(existing) || !hasExposedAirNeighbor(mine, pos)) {
                return false;
            }
        }
        if (operation.kind() == SegmentGeometry.BlockKind.LIGHT
                || operation.kind() == SegmentGeometry.BlockKind.RAIL) {
            BlockState below = mine.getBlockState(pos.below());
            if (!below.isFaceSturdy(mine, pos.below(), Direction.UP)) {
                return false;
            }
        }
        mine.setBlock(pos, replacement, Block.UPDATE_CLIENTS);
        if (operation.kind() == SegmentGeometry.BlockKind.ORE) {
            recordOreOrigin(mine, pos, replacement, operation);
        }
        return true;
    }

    private static boolean hasExposedAirNeighbor(ServerLevel mine, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (mine.getBlockState(pos.relative(direction)).isAir()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNaturalHostRock(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.STONE || block == Blocks.DEEPSLATE || block == Blocks.BLACKSTONE
                || block == Blocks.BASALT || block == Blocks.NETHERRACK || block == Blocks.TUFF
                || block == Blocks.ANDESITE || block == Blocks.DIORITE || block == Blocks.GRANITE
                || block == Blocks.GRAVEL || block == Blocks.COBBLESTONE || block == Blocks.MOSSY_COBBLESTONE
                || block == Blocks.COBBLED_DEEPSLATE;
    }

    private void recordOreOrigin(ServerLevel mine, BlockPos pos, BlockState expected,
                                 SegmentGeometry.BlockOperation operation) {
        ResourceLocation blockId = ResourceLocation.parse(operation.blockId());
        if (!mine.getBlockState(pos).equals(expected)
                || !manager.data().recordMineOre(mine, pos, blockId)) {
            throw new IllegalStateException("could not register generated ore provenance for "
                    + blockId + " at " + MineManager.describePos(pos));
        }
    }

    private BlockState blockFor(SegmentGeometry.BlockOperation operation, BlockPos pos, int y) {
        SegmentGeometry.BlockKind kind = operation.kind();
        if (kind == SegmentGeometry.BlockKind.OPEN) {
            return Blocks.CAVE_AIR.defaultBlockState();
        }
        if (kind == SegmentGeometry.BlockKind.HAZARD_BOUNDARY) {
            return Blocks.BASALT.defaultBlockState();
        }
        if (kind == SegmentGeometry.BlockKind.HAZARD_SOURCE) {
            return Blocks.LAVA.defaultBlockState();
        }
        if (kind == SegmentGeometry.BlockKind.TIMBER) {
            return Blocks.OAK_LOG.defaultBlockState();
        }
        if (kind == SegmentGeometry.BlockKind.PLANKS) {
            return Blocks.OAK_PLANKS.defaultBlockState();
        }
        if (kind == SegmentGeometry.BlockKind.FENCE) {
            return Blocks.OAK_FENCE.defaultBlockState();
        }
        if (kind == SegmentGeometry.BlockKind.RAIL) {
            return Blocks.RAIL.defaultBlockState();
        }
        if (kind == SegmentGeometry.BlockKind.LIGHT) {
            return Blocks.LANTERN.defaultBlockState();
        }
        if (kind == SegmentGeometry.BlockKind.MARKER) {
            if (operation.blockId().contains("[")) {
                try {
                    return net.minecraft.commands.arguments.blocks.BlockStateParser
                            .parseForBlock(
                                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(),
                                    operation.blockId(), false).blockState();
                } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                    throw new IllegalStateException("invalid marker block state "
                            + operation.blockId(), e);
                }
            }
            ResourceLocation blockId = ResourceLocation.parse(operation.blockId());
            Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(blockId);
            if (block == null || block == Blocks.AIR && !"minecraft:air".equals(operation.blockId())) {
                throw new IllegalStateException("unknown hub marker block " + blockId);
            }
            return block.defaultBlockState();
        }
        if (kind == SegmentGeometry.BlockKind.ORE) {
            ResourceLocation blockId = ResourceLocation.parse(operation.blockId());
            return pinnedOres.blockState(pinnedOres.entry(blockId)
                    .orElseThrow(() -> new IllegalStateException("unregistered ore catalog id " + blockId)));
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
        if ("middle".equals(region)) {
            // Deepslate layer: host rock must match the depth the ore catalog
            // already treats as "middle" tier, not a hardcoded y<0 cutoff that
            // drifted from the topology profile's actual tier boundary.
            int palette = Math.floorMod(blockHash(pos), 10);
            return switch (palette) {
                case 0, 1, 2, 3, 4, 5, 6 -> Blocks.DEEPSLATE.defaultBlockState();
                case 7, 8 -> Blocks.COBBLED_DEEPSLATE.defaultBlockState();
                default -> Blocks.TUFF.defaultBlockState();
            };
        }
        int palette = Math.floorMod(blockHash(pos), 13);
        return switch (palette) {
            case 0, 1, 2, 3, 4, 5, 6 -> Blocks.STONE.defaultBlockState();
            case 7, 8 -> Blocks.COBBLESTONE.defaultBlockState();
            case 9 -> Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            case 10 -> Blocks.ANDESITE.defaultBlockState();
            case 11 -> Blocks.DIORITE.defaultBlockState();
            default -> Blocks.GRANITE.defaultBlockState();
        };
    }

    private String regionForY(int y) {
        String found = "upper";
        String lastKnownTier = "upper";
        for (var region : TopologyProfiles.active().regions()) {
            if (OreCatalog.Tier.isKnown(region.id())) {
                lastKnownTier = region.id();
            }
            if (y <= region.topY() && y >= region.bottomY()) {
                // A custom interstitial band (e.g. "transition") resolves
                // to the nearest required tier above it rather than
                // throwing inside the ore pass mid-generation.
                found = OreCatalog.Tier.isKnown(region.id())
                        ? region.id() : lastKnownTier;
            }
        }
        return found;
    }

    private boolean isNetherrackClusterBlock(BlockPos pos) {
        MineRunData data = manager.data();
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        var chaos = TopologyProfiles.active().regions().stream()
                .filter(region -> "chaos".equals(region.id())).findFirst().orElse(null);
        if (chaos == null) {
            return false;
        }
        return NetherrackPlacementPolicy.select(data.seed(), data.generationNetherrackEnabled(),
                        data.generationNetherrackInterval(), chunkX, chunkZ,
                        chaos.topY(), chaos.bottomY())
                .filter(cluster -> NetherrackPlacementPolicy.contains(cluster,
                        pos.getX(), pos.getY(), pos.getZ()))
                .isPresent();
    }

    private long chunkHash(int x, int z) {
        return NetherrackPlacementPolicy.chunkHash(manager.data().seed(), x, z);
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

    private static long stableHash(String text) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
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
                || block == Blocks.AIR || block == Blocks.COBBLED_DEEPSLATE || block == Blocks.TUFF
                || block == Blocks.COBBLESTONE || block == Blocks.MOSSY_COBBLESTONE
                || block == Blocks.ANDESITE || block == Blocks.DIORITE || block == Blocks.GRANITE;
    }

    private void finishSegment(MineRunData data, String segmentId) {
        if (!data.completeActiveGenerationSegment()) {
            failSegment(data, "could not persist completion for " + segmentId);
            return;
        }
        activePlan = null;
        if (segmentId.equals("hub")) {
            // The generated teleporter sign labels the return console. A
            // failure here is logged but never fails the run.
            try {
                BlockPos signPos = new BlockPos(1, 42, -14);
                ServerLevel mine = manager.mineLevel();
                if (mine != null && mine.getBlockEntity(signPos)
                        instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
                    sign.updateText(text -> text
                                    .setMessage(0, net.minecraft.network.chat.Component.literal("SURFACE"))
                                    .setMessage(1, net.minecraft.network.chat.Component.literal("TELEPORTER")),
                            true);
                    mine.getChunkSource().blockChanged(signPos);
                }
            } catch (RuntimeException e) {
                LOGGER.warn("[dawnmine] could not set teleporter sign text", e);
            }
        }
        if (segmentId.startsWith("node:")) {
            TopoNode node = topology.node(segmentId.substring("node:".length()));
            if (node != null) {
                // Receipt only: the station unlocks here; encounters spawn
                // later, when a player actually approaches the room.
                registerCheckpointStation(node);
            }
        }
        LOGGER.info("[dawnmine] Generated segment {} ({} complete, {} queued).",
                segmentId, data.completedGenerationSegments().size(), data.pendingGenerationSegments().size());
        LOGGER.info("[dawnmine] Segment profile {}: {} operations, {} block writes, max {} ops in one tick, "
                        + "{} ms total / {} ms maximum slice / {} ms maximum block operation "
                        + "(budget {} operations and {} ms/tick).",
                profiledSegmentId, segmentOperations, segmentWrites, segmentMaxSliceOperations,
                String.format(Locale.ROOT, "%.2f", segmentElapsedNanos / 1_000_000.0),
                String.format(Locale.ROOT, "%.2f", segmentMaxSliceNanos / 1_000_000.0),
                String.format(Locale.ROOT, "%.2f", segmentMaxOperationNanos / 1_000_000.0),
                OPERATIONS_PER_TICK, SLICE_BUDGET_NANOS / 1_000_000L);
    }

    private void registerCheckpointStation(TopoNode node) {
        if (!node.has(com.dwurdy.dawnmine.topology.NodeFeature.CHECKPOINT)) return;
        node = physicalNode(node);
        BlockPos arrival = new BlockPos(node.pos().x(), node.pos().y(), node.pos().z());
        OwnedVolume zone = new OwnedVolume("checkpoint_" + node.id() + "_safe_zone",
                MineManager.MINE_DIMENSION, arrival.offset(-2, -1, -2), arrival.offset(2, 2, 2));
        int depth = Math.max(0, ARRIVAL.y() - node.pos().y());
        String label = node.label().isBlank() ? "Checkpoint " + node.id() : node.label();
        if (!manager.data().registerCheckpointStation(node.id(), label, node.region(), depth, arrival, zone)) {
            manager.failGeneration("checkpoint station " + node.id() + " could not be registered safely");
            LOGGER.error("[dawnmine] Completed checkpoint node {} but station registration failed", node.id());
        }
    }

    private void failSegment(MineRunData data, String detail) {
        manager.failGeneration(detail + "; inspect '/dawnmine status' before retrying");
        LOGGER.error("[dawnmine] {}", detail);
        releaseChunkRequests();
    }

    private String generationFingerprint(TopologyPlan plan, boolean netherrackEnabled,
                                        int netherrackInterval) {
        String input = GEOMETRY_VERSION + "\n" + plan.fingerprint() + "\n"
                + "netherrack=" + netherrackEnabled + ":" + netherrackInterval + "\n"
                + "ore-catalog=" + OreCatalog.active().fingerprint() + "\n"
                + "landmark-catalog=" + LandmarkCatalog.fingerprint() + "\n"
                + "motif-catalog=" + MotifCatalog.fingerprint();
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
            indexed.put(SegmentGeometry.edgeSegmentId(edge), edge);
        }
        return Map.copyOf(indexed);
    }

    private Map<Long, List<TopoNode>> indexNodes(TopologyPlan plan) {
        Map<Long, List<TopoNode>> indexed = new HashMap<>();
        for (TopoNode node : plan.nodes()) {
            TopoPos center = PassageNetwork.of(plan, ARRIVAL).center(node.id());
            node = new TopoNode(node.id(), node.kind(), node.region(), center,
                    node.features(), node.label());
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

    private TopoNode physicalNode(TopoNode node) {
        TopoPos center = PassageNetwork.of(topology, ARRIVAL, pinnedLandmarks).center(node.id());
        return new TopoNode(node.id(), node.kind(), node.region(),
                center != null ? center : node.pos(),
                node.features(), node.label());
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFF_FFFFL);
    }
}
