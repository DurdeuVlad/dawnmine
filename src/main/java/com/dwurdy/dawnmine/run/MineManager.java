package com.dwurdy.dawnmine.run;

import com.mojang.logging.LogUtils;
import com.dwurdy.dawnmine.Dawnmine;
import com.dwurdy.dawnmine.config.DawnmineConfig;
import com.dwurdy.dawnmine.encounter.MineEncounterManager;
import com.dwurdy.dawnmine.generation.OreCatalog;
import com.dwurdy.dawnmine.generation.MineGenerator;
import com.dwurdy.dawnmine.generation.MineShellGeometry;
import com.dwurdy.dawnmine.topology.MineTopology;
import com.dwurdy.dawnmine.topology.HubLayout;
import com.dwurdy.dawnmine.topology.TopologyException;
import com.dwurdy.dawnmine.topology.TopologyPlan;
import com.dwurdy.dawnmine.topology.TopologyReport;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.slf4j.Logger;

/**
 * Server-side lifecycle authority for the one shared dawnmine run.
 *
 * <p>All players resolve through {@link #resolveRunFor(ServerPlayer)} to the
 * same persisted {@link MineRunData} record. Initialization and recovery only
 * create or repair mine-owned state recorded in {@link MineRunData#ownedVolumes()};
 * they never touch the overworld beyond reading the shared spawn point.
 */
public class MineManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Dimension key of the shared Dawnmine. The dimension is shipped as a
     * datapack resource in this mod and loaded with the world. */
    public static final ResourceKey<Level> MINE_DIMENSION = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(Dawnmine.MOD_ID, "mine"));

    /** Canonical player landing inside the generated expedition hub
     * (feet block). Initialization persists the same anchor as mineArrival. */
    public static final BlockPos ARRIVAL = new BlockPos(HubLayout.LANDING.x(),
            HubLayout.LANDING.y(), HubLayout.LANDING.z());
    private static final int PAD_HALF = 2; // 5x5 pad
    /** Deterministic topology draws tried at run start: a draw whose
     * passages cannot resolve is skipped, not receipted. */
    private static final int MAX_TOPOLOGY_DRAWS = 4;
    private static final int PAD_Y = 41;
    private static final int CLEAR_Y_TO = 46;

    private static MineManager instance;

    private final MinecraftServer server;
    private final MineRunData data;

    private MineManager(MinecraftServer server) {
        this.server = server;
        DimensionDataStorage storage = server.overworld().getDataStorage();
        this.data = storage.computeIfAbsent(MineRunData.factory(), MineRunData.DATA_NAME);
    }

    public static synchronized MineManager get(MinecraftServer server) {
        if (instance == null || instance.server != server) {
            instance = new MineManager(server);
        }
        return instance;
    }

    /** Visible for tests. */
    public MineRunData data() {
        return data;
    }

    /** Fails the shared run when the world-generation subsystem detects that
     * its persisted plan or an owned-volume write is no longer trustworthy. */
    public synchronized void failGeneration(String detail) {
        data.markFailed("mine generation stopped: " + (detail == null ? "unknown error" : detail));
    }

    public ServerLevel mineLevel() {
        return server.getLevel(MINE_DIMENSION);
    }

    /** Registers the whole generated hub interior (floor to roof, including
     * the east gateway threshold) as the run's no-combat safe zone. */
    private boolean registerArrivalSafeZone() {
        OwnedVolume zone = new OwnedVolume("hub_safe_zone", MINE_DIMENSION,
                new BlockPos(HubLayout.SAFE_MIN.x(), HubLayout.SAFE_MIN.y(), HubLayout.SAFE_MIN.z()),
                new BlockPos(HubLayout.SAFE_MAX.x(), HubLayout.SAFE_MAX.y(), HubLayout.SAFE_MAX.z()));
        return data.registerSafeZone(HubLayout.SAFE_ZONE_ID, zone);
    }

    /** Re-points the current run's surface exit to a staff-bound teleporter
     * console's safe landing, re-registering the exit safe zone. */
    public synchronized boolean bindSurfaceExit(BlockPos landing) {
        if (landing == null || data.hasUnsupportedSchema()) return false;
        BlockPos previous = data.surfaceExit();
        data.setSurfaceExit(landing.immutable());
        if (data.runId().isBlank() || data.registerSurfaceExitSafeZone()) return true;
        data.setSurfaceExit(previous);
        return false;
    }

    /** The staff-bound surface teleporter landing, when one is persisted. */
    public java.util.Optional<BlockPos> teleporterLanding() {
        var binding = com.dwurdy.dawnmine.run.TeleporterData.get(server).binding();
        return java.util.Optional.ofNullable(binding == null ? null : binding.safeLanding());
    }

    /** The single shared run every player resolves to. */
    public MineRunData resolveRunFor(ServerPlayer player) {
        return data;
    }

    /** Called on server start: detect a run left mid-initialization. A run
     * left mid-RESET is deliberately not failed: {@link MineDailyReset}
     * resumes the persisted reset transaction on the next ticks. */
    public void onServerStarted() {
        if (data.status() == RunStatus.INITIALIZING) {
            data.markFailed("initialization interrupted by server restart; run '/dawnmine recover repair' to retry");
            LOGGER.warn("[dawnmine] Mine run {} was left mid-initialization and was marked FAILED for safe retry.",
                    data.runId().isEmpty() ? "(unassigned)" : data.runId());
        }
        LOGGER.info("[dawnmine] Loaded run state: status={} runId={} seed={} day={} ownedVolumes={}"
                        + " resetPhase={} lastReset={}",
                data.status(), data.runId().isEmpty() ? "(none)" : data.runId(),
                data.seed(), data.dayId().isEmpty() ? "(none)" : data.dayId(), data.ownedVolumes().size(),
                data.resetPhase(), data.lastResetDue().isEmpty() ? "(none)" : data.lastResetDue());
    }

    // ------------------------------------------------------------------
    // Initialization and recovery
    // ------------------------------------------------------------------

    public record Step(String name, boolean ok, String detail) {
        static Step ok(String name, String detail) { return new Step(name, true, detail); }
        static Step fail(String name, String detail) { return new Step(name, false, detail); }
    }

    /**
     * Idempotent initialization of the shared run. Safe to re-run after a
     * restart or a previous failure; never clears non-mine blocks and never
     * duplicates owned-state records.
     */
    public synchronized List<Step> initialize() {
        List<Step> steps = new ArrayList<>();

        if (data.hasUnsupportedSchema()) {
            steps.add(Step.fail("run record", data.lastError()));
            return steps;
        }

        if (data.resetPhase() == ResetPhase.CLEANING) {
            // Creating a run while the daily cleanup still owns the terrain
            // would admit a run over unrestored blocks. The scheduler finishes
            // cleanup first and runs this same path itself during FINALIZING.
            steps.add(Step.fail("daily reset", "the scheduled reset cleanup is still running ("
                    + data.resetCursor() + " columns done); it finishes on its own - "
                    + "see '/dawnmine reset status'"));
            return steps;
        }

        if (data.status() == RunStatus.FAILED && !data.resetInProgress()
                && !data.runId().isBlank()
                && data.ownedVolumes().stream()
                        .anyMatch(volume -> volume.dimension().equals(MINE_DIMENSION))) {
            // A failed run that still owns mine terrain must be restored by the
            // owned cleanup scheduler first - never overlay new preparation on
            // top of old caves. The record stays intact until CLEANING/FINALIZING.
            MineDailyReset.RequestResult request = MineDailyReset.get(server)
                    .requestManualReset();
            if (request == MineDailyReset.RequestResult.STARTED) {
                steps.add(Step.ok("owned cleanup",
                        "previous mine-owned terrain must be restored before new "
                        + "preparation; entry is locked - track '/dawnmine reset status'"));
            } else {
                steps.add(Step.fail("owned cleanup",
                        "could not start owned cleanup for the failed run (" + request
                        + "); entry stays locked - see '/dawnmine reset status'"));
            }
            return steps;
        }

        if (data.status() == RunStatus.READY) {
            String shellError = MineShellGeometry.configurationError(mineLevel(),
                    DawnmineConfig.mineRegionRadiusChunks());
            if (shellError.isEmpty()) {
                shellError = MineShellGeometry.persistedBoundsError(data,
                        DawnmineConfig.mineRegionRadiusChunks());
            }
            if (!shellError.isEmpty()) {
                data.markFailed("mine boundary validation failed: " + shellError);
                steps.add(Step.fail("boundary shell", shellError));
                return steps;
            }
            Check topology = checkTopology(mineLevel());
            if (!topology.ok()) {
                data.markFailed("READY run topology check failed: " + topology.detail());
                steps.add(Step.fail("topology", topology.detail()));
                return steps;
            }
            steps.add(Step.ok("existing run", "already initialized as run " + data.runId() + "; nothing was changed"));
            steps.add(verifyArrivalPad());
            if (!registerArrivalSafeZone()) {
                data.markFailed("could not register the mine arrival safe zone");
                steps.add(Step.fail("arrival safe zone", "could not register the bounded arrival safe zone"));
            }
            if (!data.registerSurfaceExitSafeZone()) {
                data.markFailed("could not register the fixed overworld exit safe zone");
                steps.add(Step.fail("surface exit safe zone", "could not register the bounded recorded-exit safe zone"));
            }
            return steps;
        }

        ServerLevel mine = mineLevel();
        if (mine == null) {
            steps.add(Step.fail("dimension", "dimension " + MINE_DIMENSION.location()
                    + " is not loaded; restart the server so the mod's dimension data pack is applied, then retry"));
            data.markFailed("dimension not loaded");
            return steps;
        }
        steps.add(Step.ok("dimension", "dimension " + MINE_DIMENSION.location() + " is loaded"));

        String shellError = MineShellGeometry.configurationError(mine,
                DawnmineConfig.mineRegionRadiusChunks());
        if (!shellError.isEmpty()) {
            data.markFailed("mine boundary validation failed: " + shellError);
            steps.add(Step.fail("boundary shell", shellError));
            return steps;
        }

        OreCatalog.Completeness oreCatalog = OreCatalog.active().completeness(mine);
        if (!oreCatalog.complete()) {
            String detail = OreCatalog.describe(oreCatalog);
            data.markFailed("ore catalog is incomplete: " + detail);
            steps.add(Step.fail("ore catalog", detail));
            return steps;
        }

        try {
            long sequence = data.runSequence() + 1;
            String dayId = LocalDate.now(DawnmineConfig.resetZoneId()).toString();
            String runId = dayId + "-" + sequence;
            long worldSeed = server.getWorldData().worldGenOptions().seed();
            long seed = data.computeNextSeed(worldSeed);
            MineEncounterManager.cleanupRun(mine, data, data.runId());
            data.beginNewRun(runId, sequence, seed, dayId);
            steps.add(Step.ok("run record", "created run " + runId + " for day " + dayId + " (seed recorded)"));

            int radius = DawnmineConfig.mineRegionRadiusChunks();
            data.setBounds(-radius, -radius, radius - 1, radius - 1);
            BlockPos regionMin = new BlockPos(-radius * 16, mine.getMinBuildHeight(), -radius * 16);
            BlockPos regionMax = new BlockPos(radius * 16 - 1, mine.getMinBuildHeight() + mine.getHeight() - 1, radius * 16 - 1);
            data.addOwnedVolume(new OwnedVolume("active_region", MINE_DIMENSION, regionMin, regionMax));
            steps.add(Step.ok("ownership", "recorded active region of " + (2 * radius) + "x" + (2 * radius)
                    + " chunk columns centred on the arrival point"));

            var binding = com.dwurdy.dawnmine.run.TeleporterData.get(server).binding();
            BlockPos exit = binding != null ? binding.safeLanding()
                    : server.overworld().getSharedSpawnPos();
            data.setSurfaceExit(exit);
            steps.add(Step.ok("surface exit", binding != null
                    ? "recorded bound teleporter landing at " + describePos(exit)
                    : "recorded overworld spawn at " + describePos(exit)));
            if (!data.registerSurfaceExitSafeZone()) {
                throw new IllegalStateException("could not register the fixed overworld exit safe zone");
            }

            Step topology = planTopology(mine);
            steps.add(topology);
            if (!topology.ok()) {
                data.markFailed("topology planning failed");
                return steps;
            }

            // M2a's complete topology must pass its pre-mutation gate before
            // even the M1 arrival pad is written into the mine dimension.
            Step arrivalPad = placeArrivalPad(mine);
            steps.add(arrivalPad);
            if (!arrivalPad.ok()) {
                data.markFailed("arrival pad placement failed");
                return steps;
            }
            data.setMineArrival(ARRIVAL);
            data.addOwnedVolume(new OwnedVolume("arrival_pad", MINE_DIMENSION,
                    new BlockPos(ARRIVAL.getX() - PAD_HALF, PAD_Y, ARRIVAL.getZ() - PAD_HALF),
                    new BlockPos(ARRIVAL.getX() + PAD_HALF, CLEAR_Y_TO, ARRIVAL.getZ() + PAD_HALF)));
            if (!registerArrivalSafeZone()) {
                throw new IllegalStateException("could not register the bounded arrival safe zone");
            }
            data.markStatus(RunStatus.READY);
            steps.add(Step.ok("initialized", "run " + data.runId()
                    + " is initialized; the full mine is now prepared before entry opens"));
        } catch (Exception e) {
            data.markFailed("setup stopped unexpectedly: " + e.getMessage());
            steps.add(Step.fail("setup", "stopped unexpectedly: " + e.getMessage()
                    + "; nothing outside mine-owned volumes was touched; run '/dawnmine recover repair' to retry"));
            LOGGER.warn("[dawnmine] Mine initialization failed", e);
        }
        return steps;
    }

    private Step placeArrivalPad(ServerLevel mine) {
        try {
            for (int dx = -PAD_HALF; dx <= PAD_HALF; dx++) {
                for (int dz = -PAD_HALF; dz <= PAD_HALF; dz++) {
                    mine.setBlock(new BlockPos(ARRIVAL.getX() + dx, PAD_Y, ARRIVAL.getZ() + dz),
                            Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                    for (int y = PAD_Y + 1; y <= CLEAR_Y_TO; y++) {
                        mine.setBlock(new BlockPos(ARRIVAL.getX() + dx, y, ARRIVAL.getZ() + dz),
                                Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
            return Step.ok("arrival pad", "5x5 stone pad verified at " + describePos(ARRIVAL)
                    + " in " + MINE_DIMENSION.location());
        } catch (Exception e) {
            return Step.fail("arrival pad", "could not place pad at " + describePos(ARRIVAL)
                    + ": " + e.getMessage());
        }
    }

    private Step verifyArrivalPad() {
        ServerLevel mine = mineLevel();
        if (mine == null) {
            return Step.fail("arrival pad", "mine dimension is not loaded");
        }
        if (isArrivalSafe(mine)) {
            return Step.ok("arrival pad", "pad is intact at " + describePos(ARRIVAL));
        }
        Step repaired = placeArrivalPad(mine);
        return new Step("arrival pad", repaired.ok(),
                repaired.ok() ? "pad was missing and has been re-placed at " + describePos(ARRIVAL)
                        : repaired.detail());
    }

    private boolean isArrivalSafe(ServerLevel mine) {
        return isArrivalSafe(mine, data.mineArrival() != null ? data.mineArrival() : ARRIVAL);
    }

    // ------------------------------------------------------------------
    // Topology (M2a): deterministic plan, validated before any block work
    // ------------------------------------------------------------------

    /** Plans the run topology from the recorded seed and bounds, validates
     * it, and proves its passages resolve before receipting. Resolution is
     * a constraint pass over a random draw and some draws are
     * unsatisfiable - bound-pinned connector doors against ceiling-capped
     * columns in one merge cluster - so the step walks a deterministic
     * draw sequence and receipts the first plan that resolves. Draw 0 is
     * the base seed; every later replan reads the recorded offset back, so
     * a run always reproduces the topology it was verified with. Nothing
     * is placed: the only state written is the topology receipt (profile,
     * fingerprint, counts, draw offset) in the run record. */
    private Step planTopology(ServerLevel mine) {
        com.dwurdy.dawnmine.topology.TopoPos arrival = new com.dwurdy.dawnmine.topology.TopoPos(
                ARRIVAL.getX(), ARRIVAL.getY(), ARRIVAL.getZ());
        IllegalStateException failure = null;
        for (int draw = 0; draw < MAX_TOPOLOGY_DRAWS; draw++) {
            TopologyPlan plan;
            try {
                plan = MineTopology.plan(data, mine, draw);
            } catch (TopologyException e) {
                return Step.fail("topology", e.getMessage());
            }
            TopologyReport report = MineTopology.validate(plan);
            if (!report.ok()) {
                return Step.fail("topology", "planned topology failed validation: "
                        + report.describe() + "; fix the profile data or bounds, then retry");
            }
            try {
                com.dwurdy.dawnmine.generation.PassageNetwork.of(plan, arrival,
                        com.dwurdy.dawnmine.generation.LandmarkCatalog.definitions());
            } catch (IllegalStateException x) {
                failure = x;
                LOGGER.warn("[dawnmine] topology draw {}/{} cannot resolve "
                        + "passages - re-drawing ({})",
                        draw + 1, MAX_TOPOLOGY_DRAWS, x.getMessage());
                continue;
            }
            data.setTopology(plan.profileId(), plan.fingerprint(),
                    plan.nodes().size(), plan.edges().size(), draw);
            return Step.ok("topology", "planned " + plan.summary()
                    + (draw > 0 ? " (draw " + draw + ")" : "")
                    + "; no blocks placed (generation is M2b)");
        }
        return Step.fail("topology", "no topology draw produced resolvable "
                + "passages after " + MAX_TOPOLOGY_DRAWS + " attempts: "
                + failure.getMessage());
    }

    /** Replans deterministically and compares against the persisted receipt,
     * proving the stored fingerprint still matches (seed + profile + bounds). */
    private Check checkTopology(ServerLevel mine) {
        if (!data.hasValidTopologyFingerprint()) {
            if (!data.topologyFingerprint().isEmpty()) {
                return Check.fail("topology",
                        "recorded topology fingerprint is malformed; run '/dawnmine recover repair'");
            }
            return Check.fail("topology",
                    "no topology is recorded for this run; run '/dawnmine recover repair'");
        }
        if (mine == null) {
            return Check.fail("topology",
                    "mine dimension is not loaded; the recorded topology cannot be verified");
        }
        try {
            TopologyPlan plan = MineTopology.plan(data, mine);
            TopologyReport report = MineTopology.validate(plan);
            if (!report.ok()) {
                return Check.fail("topology",
                        "replanned topology fails validation: " + report.describe());
            }
            if (!plan.fingerprint().equals(data.topologyFingerprint())) {
                return Check.fail("topology",
                        "replanned topology fingerprint " + plan.fingerprint().substring(0, 12)
                                + " differs from the recorded " + data.topologyFingerprint().substring(0, 12)
                                + "; profile or bounds changed since planning - run '/dawnmine recover repair'");
            }
            return Check.pass("topology", "plan verified: " + plan.summary());
        } catch (TopologyException e) {
            return Check.fail("topology", e.getMessage());
        }
    }

    /** The pad is the 5x5 solid floor directly beneath the arrival point,
     * with clear air at feet and head level. */
    private boolean isArrivalSafe(ServerLevel mine, BlockPos arrival) {
        for (int dx = -PAD_HALF; dx <= PAD_HALF; dx++) {
            for (int dz = -PAD_HALF; dz <= PAD_HALF; dz++) {
                BlockPos padBlock = new BlockPos(arrival.getX() + dx, arrival.getY() - 1, arrival.getZ() + dz);
                if (!mine.getBlockState(padBlock).isSolidRender(mine, padBlock)) {
                    return false;
                }
            }
        }
        return mine.getBlockState(arrival).isAir() && mine.getBlockState(arrival.above()).isAir();
    }

    // ------------------------------------------------------------------
    // Validation and recovery
    // ------------------------------------------------------------------

    public enum Severity { PASS, NOTE, FAIL }

    public record Check(String name, Severity severity, String detail) {
        public boolean ok() { return severity != Severity.FAIL; }
        static Check pass(String name, String detail) { return new Check(name, Severity.PASS, detail); }
        static Check warn(String name, String detail) { return new Check(name, Severity.NOTE, detail); }
        static Check fail(String name, String detail) { return new Check(name, Severity.FAIL, detail); }
    }

    public synchronized List<Check> validate() {
        List<Check> checks = new ArrayList<>();
        ServerLevel mine = mineLevel();
        checks.add(mine != null
                ? Check.pass("dimension", MINE_DIMENSION.location() + " is loaded")
                : Check.fail("dimension", MINE_DIMENSION.location()
                        + " is not loaded; restart the server, then run '/dawnmine setup validate' again"));

        String shellError = MineShellGeometry.configurationError(mine,
                DawnmineConfig.mineRegionRadiusChunks());
        checks.add(shellError.isEmpty()
                ? Check.pass("boundary shell", "bounded flat generator matches the configured mine radius")
                : Check.fail("boundary shell", shellError));

        OreCatalog.Completeness oreCatalog = mine == null ? null : OreCatalog.active().completeness(mine);
        checks.add(oreCatalog == null
                ? Check.fail("ore catalog", "mine dimension is not loaded; catalog cannot be checked")
                : oreCatalog.complete()
                        ? Check.pass("ore catalog", OreCatalog.describe(oreCatalog))
                        : Check.fail("ore catalog", OreCatalog.describe(oreCatalog)));

        checks.add(DawnmineConfig.isValidTimeZone(DawnmineConfig.resetTimeZoneName())
                ? Check.pass("reset timezone", "reset clock uses " + DawnmineConfig.resetZoneId()
                        + (DawnmineConfig.resetTimeZoneName().equalsIgnoreCase("server")
                                ? " (server default)" : ""))
                : Check.fail("reset timezone", "'" + DawnmineConfig.resetTimeZoneName()
                        + "' is not a valid time zone; fix with '/dawnmine config set resetTimeZone Europe/Bucharest' or 'server'"));

        switch (data.status()) {
            case UNINITIALIZED -> checks.add(Check.warn("run state",
                    "no run exists yet; run '/dawnmine setup initialize' to create the shared mine"));
            case INITIALIZING -> checks.add(Check.fail("run state",
                    "initialization is in progress; if the server was restarted, run '/dawnmine recover repair'"));
            case RESETTING -> checks.add(Check.warn("run state",
                    "daily reset in progress (phase " + data.resetPhase() + ", " + data.resetCursor()
                            + " columns done); entry is locked until the new run is ready - "
                            + "see '/dawnmine reset status'"));
            case FAILED -> checks.add(Check.fail("run state",
                    "last initialization failed: " + data.lastError()));
            case READY -> validateReadyRun(mine, checks);
        }

        String today = LocalDate.now(DawnmineConfig.resetZoneId()).toString();
        if (data.status() == RunStatus.READY && !data.dayId().equals(today)) {
            checks.add(Check.warn("run day",
                    "active run is for " + data.dayId() + " but today is " + today
                            + "; the scheduled daily reset has not run yet today"));
        }
        return checks;
    }

    private void validateReadyRun(ServerLevel mine, List<Check> checks) {
        String shellError = MineShellGeometry.persistedBoundsError(data,
                DawnmineConfig.mineRegionRadiusChunks());
        checks.add(shellError.isEmpty()
                ? Check.pass("shell ownership", "saved active bounds match the enclosing bedrock shell")
                : Check.fail("shell ownership", shellError));
        OreCatalog.Completeness oreCatalog = mine == null ? null : OreCatalog.active().completeness(mine);
        checks.add(oreCatalog != null && oreCatalog.complete()
                ? Check.pass("ore catalog", OreCatalog.describe(oreCatalog))
                : Check.fail("ore catalog", oreCatalog == null
                        ? "mine dimension is not loaded" : OreCatalog.describe(oreCatalog)));
        if (data.runId().isEmpty() || data.seed() == 0) {
            checks.add(Check.fail("run record", "run is READY but run id or seed is missing; run '/dawnmine recover repair'"));
        } else {
            checks.add(Check.pass("run record", "run " + data.runId() + " (day " + data.dayId() + ", seed recorded)"));
        }
        if (data.boundMaxChunkX() < data.boundMinChunkX() || data.boundMaxChunkZ() < data.boundMinChunkZ()) {
            checks.add(Check.fail("bounds", "recorded region bounds are empty"));
        } else {
            int sizeX = data.boundMaxChunkX() - data.boundMinChunkX() + 1;
            int sizeZ = data.boundMaxChunkZ() - data.boundMinChunkZ() + 1;
            checks.add(Check.pass("bounds", "active region is " + sizeX + "x" + sizeZ + " chunk columns"));
        }
        if (data.ownedVolumes().isEmpty()) {
            checks.add(Check.fail("ownership", "no mine-owned volumes are recorded; cleanup boundary is unknown"));
        } else {
            checks.add(Check.pass("ownership", data.ownedVolumes().size() + " owned volume(s) recorded"));
        }
        if (data.mineArrival() == null) {
            checks.add(Check.fail("arrival", "no arrival point recorded"));
        } else if (mine == null || !isArrivalSafe(mine)) {
            checks.add(Check.fail("arrival", "arrival pad is damaged; run '/dawnmine recover repair'"));
        } else {
            checks.add(Check.pass("arrival", "arrival pad is safe at " + describePos(data.mineArrival())));
        }
        if (data.surfaceExit() == null) {
            checks.add(Check.warn("surface exit", "no surface exit recorded; '/dawnmine leave' falls back to world spawn"));
        } else {
            checks.add(Check.pass("surface exit", "surface exit recorded at " + describePos(data.surfaceExit())));
        }
        checks.add(checkTopology(mine));
        var prep = preparationProgress();
        checks.add(prep.complete()
                ? Check.pass("preparation", "all " + prep.expected()
                        + " expected segments complete; the run is fully prepared")
                : Check.warn("preparation", "whole-run preparation is incomplete: " + prep.completed()
                        + "/" + prep.expected() + " segments done (" + prep.percent()
                        + "%); entry stays closed until every segment is generated"));
        checks.add(Check.pass("world geometry", data.completedGenerationSegments().size()
                + " segment(s) complete, " + data.pendingGenerationSegments().size() + " queued"
                + (data.activeGenerationSegment().isEmpty() ? "" : ", active "
                        + data.activeGenerationSegment() + " at operation " + data.activeGenerationCursor())));
        checks.add(Check.pass("reset scheduling",
                "daily reset is scheduled for " + effectiveResetDescription()
                        + (data.lastResetDue().isEmpty()
                                ? "; no completed reset recorded yet"
                                : "; last completed " + data.lastResetDue())));
    }

    /**
     * Safe recovery: inspects persisted state and repairs only mine-owned
     * state. Returns true when the run is usable afterwards.
     */
    public synchronized List<Step> recoverRepair() {
        List<Step> steps = new ArrayList<>();
        steps.add(Step.ok("inspect", "current status is " + data.status()
                + (data.runId().isEmpty() ? "" : " for run " + data.runId())));
        if (data.resetInProgress()) {
            // A live reset transaction must finish (or be re-armed), never be
            // bypassed: initialization here would admit a run over unrestored
            // mine-owned terrain.
            data.clearResetAttempts();
            steps.add(Step.ok("daily reset", "a daily reset is in progress (phase "
                    + data.resetPhase() + ", " + data.resetCursor() + " columns done); its retries were "
                    + "re-armed and it continues automatically - see '/dawnmine reset status'"));
            return steps;
        }
        if (data.status() == RunStatus.READY) {
            // A READY run whose record failed validation is corrupt; drop it
            // to FAILED so the initialization path rebuilds every field.
            List<Check> checks = validate();
            long failures = checks.stream().filter(c -> c.severity() == Severity.FAIL).count();
            if (failures > 0) {
                data.markFailed("recover found " + failures + " corrupt field(s) in the READY run record");
                steps.add(Step.fail("inspect", "READY run record failed " + failures
                        + " validation check(s); re-running initialization"));
            } else {
                steps.add(verifyArrivalPad());
                steps.add(Step.ok("recover", "run is healthy; no changes made"));
                return steps;
            }
        }
        // UNINITIALIZED, INITIALIZING (stale), FAILED, or corrupt READY: re-run idempotent init.
        steps.addAll(initialize());
        return steps;
    }

    // ------------------------------------------------------------------
    // Entry and exit
    // ------------------------------------------------------------------

    public enum EnterResult { ENTERED, NOT_READY, ARRIVAL_UNSAFE, ALREADY_INSIDE, FAILED }

    public record EnterOutcome(EnterResult result, String detail) {
        public boolean ok() { return result == EnterResult.ENTERED; }
    }

    public synchronized EnterOutcome enter(ServerPlayer player) {
        if (player.level().dimension().equals(MINE_DIMENSION)) {
            return new EnterOutcome(EnterResult.ALREADY_INSIDE,
                    "you are already inside the Dawnmine");
        }
        if (data.resetInProgress()) {
            // A persisted reset phase owns the mine even when a crash left the
            // run marked READY mid-finalize; entry stays locked until the
            // transaction records its occurrence.
            return new EnterOutcome(EnterResult.NOT_READY,
                    "the mine is resetting for a new day; it reopens in a few minutes");
        }
        if (!data.status().entryAllowed()) {
            return new EnterOutcome(EnterResult.NOT_READY,
                    switch (data.status()) {
                        case FAILED -> "the mine is being repaired by staff; please try again later";
                        case RESETTING -> "the mine is resetting for a new day; it reopens in a few minutes";
                        default -> "the mine is not open yet; staff can open it with '/dawnmine setup initialize'";
                    });
        }
        if (!preparationComplete()) {
            var prep = preparationProgress();
            return new EnterOutcome(EnterResult.NOT_READY,
                    "the mine is still being prepared for today's run"
                            + (prep.expected() > 0 ? " (" + prep.percent() + "% done)"
                            : "") + " - it opens when preparation finishes");
        }
        ServerLevel mine = mineLevel();
        BlockPos target = data.mineArrival() != null ? data.mineArrival() : ARRIVAL;
        // isArrivalSafe reads blocks on demand, which loads the pad column;
        // gating on hasChunkAt wrongly denied the first entrant whenever the
        // generated pad chunk had simply unloaded from memory.
        if (mine == null || !isArrivalSafe(mine, target)) {
            return new EnterOutcome(EnterResult.ARRIVAL_UNSAFE,
                    "the mine entrance is not safe right now; staff have been asked to check it");
        }
        data.setReturnPoint(player.getUUID(), new MineRunData.ReturnPoint(
                player.level().dimension(), player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot()));
        try {
            teleportWithMenuSafety(player, mine, target.getX() + 0.5, target.getY(),
                    target.getZ() + 0.5, 180.0F, 0.0F);
        } catch (Exception e) {
            data.clearReturnPoint(player.getUUID());
            return new EnterOutcome(EnterResult.FAILED,
                    "could not move you into the mine (" + e.getMessage() + "); staff can check '/dawnmine status'");
        }
        return new EnterOutcome(EnterResult.ENTERED, "arrived in run " + data.runId());
    }

    public enum CheckpointResult { TRAVELED, INVALID_STATE, NOT_FOUND, FAILED }

    public record CheckpointOutcome(CheckpointResult result, String detail,
                                    MineRunData.CheckpointStation station, boolean usedFallback) {}

    /** Travels to a generated, shared checkpoint without force-loading its chunk. */
    public synchronized CheckpointOutcome travelToCheckpoint(ServerPlayer player, String stationId) {
        if (data.resetInProgress()
                || data.status() != RunStatus.READY || !data.status().entryAllowed()
                || !preparationComplete()) {
            return new CheckpointOutcome(CheckpointResult.INVALID_STATE,
                    "the mine is initializing, being prepared, resetting, or closed; checkpoint travel is unavailable",
                    null, false);
        }
        MineRunData.CheckpointStation station = data.checkpointStation(stationId);
        if (station == null) {
            return new CheckpointOutcome(CheckpointResult.NOT_FOUND,
                    "that checkpoint has not been generated in this shared run", null, false);
        }
        ServerLevel mine = mineLevel();
        if (mine == null) {
            return new CheckpointOutcome(CheckpointResult.FAILED,
                    "the mine dimension is unavailable", station, false);
        }
        boolean inside = player.level().dimension().equals(MINE_DIMENSION);
        if (!inside && !data.status().entryAllowed()) {
            return new CheckpointOutcome(CheckpointResult.INVALID_STATE,
                    "the mine is not open for entry", station, false);
        }

        BlockPos target = station.arrival();
        boolean fallback = false;
        if (!mine.hasChunkAt(target)) {
            target = data.mineArrival();
            fallback = true;
        } else if (!data.isInSafeZone(mine, target) || !isSafeLanding(mine, target)) {
            data.removeCheckpointStation(station.id());
            target = data.mineArrival();
            fallback = true;
        }
        if (target == null || !isArrivalSafe(mine, target)) {
            return new CheckpointOutcome(CheckpointResult.FAILED,
                    "the checkpoint and entrance are unavailable; you were not moved", station, fallback);
        }
        if (!inside) {
            data.setReturnPoint(player.getUUID(), new MineRunData.ReturnPoint(
                    player.level().dimension(), player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot()));
        }
        try {
            teleportWithMenuSafety(player, mine, target.getX() + 0.5, target.getY(),
                    target.getZ() + 0.5, 0.0F, 0.0F);
        } catch (Exception exception) {
            if (!inside) data.clearReturnPoint(player.getUUID());
            return new CheckpointOutcome(CheckpointResult.FAILED,
                    "the teleport failed; ask staff to check the mine", station, fallback);
        }
        String depth = "about " + station.depth() + " blocks below the entrance in " + station.region();
        String routeHint = checkpointRouteHint(station.id());
        String detail = fallback
                ? "That station chunk is unavailable or unsafe, so you arrived at the mine entrance. "
                        + "Use '/dawnmine leave' to bank/extract, or '/dawnmine checkpoints' to choose another station."
                : "Arrived at " + station.label() + " (" + depth + "). " + routeHint
                        + " The route gets riskier with depth; "
                        + "use '/dawnmine leave' to bank/extract, or pick a deeper checkpoint to push farther.";
        return new CheckpointOutcome(CheckpointResult.TRAVELED, detail, station, fallback);
    }

    private String checkpointRouteHint(String stationId) {
        try {
            ServerLevel mine = mineLevel();
            if (mine == null) return "Next route risk is unknown.";
            TopologyPlan plan = MineTopology.plan(data, mine);
            int index = plan.mainPath().indexOf(stationId);
            if (index < 0) return "Next route risk is unknown; follow the connected tunnel.";
            if (index + 1 >= plan.mainPath().size()) {
                return "This is the deepest planned main-route station; connected side routes remain explorable.";
            }
            var next = plan.node(plan.mainPath().get(index + 1));
            if (next == null) return "Next route risk is unknown.";
            int nextDepth = Math.max(0, ARRIVAL.getY() - next.pos().y());
            return "Next main-route step: " + next.region() + " at about " + nextDepth + " blocks deep.";
        } catch (RuntimeException exception) {
            return "Next route risk is unknown; the mine remains traversable from here.";
        }
    }

    public enum LeaveResult { LEFT, NOT_INSIDE, FAILED }

    public synchronized LeaveResult leave(ServerPlayer player) {
        if (!player.level().dimension().equals(MINE_DIMENSION)) {
            return LeaveResult.NOT_INSIDE;
        }
        ServerLevel overworld = server.overworld();
        ServerLevel destination = overworld;
        double x = 0.0, y = 0.0, z = 0.0;
        float yaw = 0.0F, pitch = 0.0F;
        MineRunData.ReturnPoint back = data.returnPoint(player.getUUID());
        ServerLevel backLevel = back == null ? null : server.getLevel(back.dimension());
        BlockPos landing = null;
        if (back != null && backLevel != null) {
            landing = findSafeLanding(backLevel, BlockPos.containing(back.x(), back.y(), back.z()));
            if (landing != null) {
                destination = backLevel;
                x = back.x(); y = landing.getY(); z = back.z();
                yaw = back.yaw(); pitch = back.pitch();
            }
        }
        if (landing == null) {
            // No usable return point (or it is no longer safe to stand on):
            // fall back to the recorded surface exit, then world spawn.
            for (BlockPos candidate : new BlockPos[] {
                    data.surfaceExit() != null ? data.surfaceExit() : overworld.getSharedSpawnPos(),
                    overworld.getSharedSpawnPos()}) {
                landing = findSafeLanding(destination, candidate);
                if (landing != null) {
                    x = landing.getX() + 0.5; y = landing.getY(); z = landing.getZ() + 0.5;
                    break;
                }
            }
        }
        if (landing == null) {
            // Nothing in the scanned columns can be stood on (open water,
            // void, or a removed surface). Evacuation must not leave a player
            // inside a resetting mine just because the landing is awkward:
            // put them at the recorded surface exit, else world spawn, and
            // let vanilla handle the wet landing.
            BlockPos base = data.surfaceExit() != null
                    ? data.surfaceExit() : overworld.getSharedSpawnPos();
            landing = base;
            destination = overworld;
            x = base.getX() + 0.5;
            y = base.getY();
            z = base.getZ() + 0.5;
        }
        try {
            teleportWithMenuSafety(player, destination, x, y, z, yaw, pitch);
        } catch (Exception e) {
            return LeaveResult.FAILED;
        }
        data.clearReturnPoint(player.getUUID());
        return LeaveResult.LEFT;
    }

    /**
     * Evacuates a player who logged in inside the mine while being there is
     * unsafe: a reset transaction is in flight, the run is not enterable, or
     * their saved position is now inside solid restored terrain. Covers the
     * offline-at-reset login — online occupants are evacuated by the reset
     * driver itself, but a returning player's saved position can be entombed
     * by rebuilt columns with no evacuation pass left to catch them.
     */
    public synchronized void evacuateUnsafeMineLogin(ServerPlayer player) {
        if (!player.level().dimension().equals(MINE_DIMENSION)) {
            return;
        }
        boolean resetting = data.resetInProgress();
        boolean closed = !data.status().entryAllowed() || !preparationComplete();
        if (!resetting && !closed && !isEntombed(player)) {
            return;
        }
        LeaveResult result = leave(player);
        if (result == LeaveResult.LEFT) {
            player.sendSystemMessage(Component.literal(
                    "The Dawnmine reset for a new day while you were away - you were moved to the surface "
                            + "with everything you carried."));
            LOGGER.info("[dawnmine] Evacuated returning player {} from the mine "
                            + "(resetting={} status={}).",
                    player.getGameProfile().getName(), resetting, data.status());
        } else {
            LOGGER.warn("[dawnmine] Returning player {} stayed inside the mine after login "
                            + "(resetting={} status={} result={}); they can still use '/dawnmine leave'.",
                    player.getGameProfile().getName(), resetting, data.status(), result);
        }
    }

    /** True when the player's feet or head are inside a solid block, i.e. the
     * restored flat baseline swallowed their saved position. */
    private boolean isEntombed(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        BlockPos pos = player.blockPosition();
        return level.getBlockState(pos).isSolidRender(level, pos)
                || level.getBlockState(pos.above()).isSolidRender(level, pos.above());
    }

    /** The nearest position where a player can stand without falling or
     * suffocating, searching down first (respawn-style) then a few blocks up. */
    /**
     * Canonical safe teleport: closes the player's menu on the SAFE side of
     * the dimension hop. Travelling INTO the mine closes before the jump
     * (overflow drops at the overworld source); travelling OUT closes only
     * after the move succeeds, so a cancelled hop preserves the menu,
     * cursor, and recorded return point, while a forced leave still drops
     * menu overflow at the surface rather than inside the resettable mine.
     * A cancelled cross-dimension move (teleportTo may return silently when
     * EntityTravelToDimensionEvent is cancelled) is detected by comparing
     * the player's post-call level and thrown into the caller's existing
     * FAILED path.
     */
    private void teleportWithMenuSafety(ServerPlayer player, ServerLevel destination,
                                        double x, double y, double z, float yaw, float pitch) {
        boolean fromMine = player.level().dimension().equals(MINE_DIMENSION);
        if (!fromMine) {
            player.closeContainer();
        }
        player.teleportTo(destination, x, y, z, yaw, pitch);
        if (player.serverLevel() != destination) {
            throw new IllegalStateException("another server rule blocked travel");
        }
        if (fromMine) {
            player.closeContainer();
        }
    }

    private BlockPos findSafeLanding(ServerLevel level, BlockPos base) {
        // The chunk must be loaded to judge the landing; block reads below
        // load it on demand. Refusing unloaded chunks made evacuation fail
        // whenever a return point or spawn sat in an unloaded column — on a
        // dedicated server spawn chunks are not force-loaded, so a daily
        // reset could leave occupants inside while terrain restored.
        if (isSafeLanding(level, base)) {
            return base;
        }
        for (int dy = -1; dy >= -32 && base.getY() + dy > level.getMinBuildHeight(); dy--) {
            BlockPos pos = base.offset(0, dy, 0);
            if (isSafeLanding(level, pos)) {
                return pos;
            }
        }
        for (int dy = 1; dy <= 8 && base.getY() + dy < level.getMaxBuildHeight() - 1; dy++) {
            BlockPos pos = base.offset(0, dy, 0);
            if (isSafeLanding(level, pos)) {
                return pos;
            }
        }
        return null;
    }

    /** True when a player can stand at pos without falling or suffocating:
     * solid ground below and two air blocks at/below head level. */
    private boolean isSafeLanding(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos.below()).isSolidRender(level, pos.below())
                && level.getBlockState(pos).isAir()
                && level.getBlockState(pos.above()).isAir();
    }

    // ------------------------------------------------------------------
    // Preparation gating (0.3 whole-run pre-generation)
    // ------------------------------------------------------------------

    /** Preparation is a separate derived gate on top of READY: the run may
     * be READY while its whole-run generation is still queued. Fails closed
     * whenever the generator cannot prove the expected segment set. */
    public boolean preparationComplete() {
        return MineGenerator.get(server).preparationComplete();
    }

    public com.dwurdy.dawnmine.generation.PreparationPlan.Progress preparationProgress() {
        return MineGenerator.get(server).preparation();
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    public List<ServerPlayer> occupants() {
        List<ServerPlayer> inside = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level().dimension().equals(MINE_DIMENSION)) {
                inside.add(player);
            }
        }
        return inside;
    }

    /** True when the position lies inside any recorded mine-owned volume. */
    public boolean isOwned(Level level, BlockPos pos) {
        for (OwnedVolume volume : data.ownedVolumes()) {
            if (volume.contains(level, pos)) {
                return true;
            }
        }
        return false;
    }

    /** True when the position lies outside every recorded mine-owned volume. */
    public boolean isOutsideOwnedArea(Level level, BlockPos pos) {
        return !isOwned(level, pos);
    }

    public static String describePos(BlockPos pos) {
        return "[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]";
    }

    public String effectiveResetDescription() {
        return String.format("%02d:%02d %s", DawnmineConfig.resetHour(),
                DawnmineConfig.resetMinute(), DawnmineConfig.resetZoneId());
    }
}
