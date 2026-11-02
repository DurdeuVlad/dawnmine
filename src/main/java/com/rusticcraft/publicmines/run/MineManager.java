package com.rusticcraft.publicmines.run;

import com.mojang.logging.LogUtils;
import com.rusticcraft.publicmines.PublicMines;
import com.rusticcraft.publicmines.config.PublicMineConfig;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
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
 * Server-side lifecycle authority for the one shared public-mine run.
 *
 * <p>All players resolve through {@link #resolveRunFor(ServerPlayer)} to the
 * same persisted {@link MineRunData} record. Initialization and recovery only
 * create or repair mine-owned state recorded in {@link MineRunData#ownedVolumes()};
 * they never touch the overworld beyond reading the shared spawn point.
 */
public class MineManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Dimension key of the shared public mine. The dimension is shipped as a
     * datapack resource in this mod and loaded with the world. */
    public static final ResourceKey<Level> MINE_DIMENSION = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(PublicMines.MOD_ID, "public_mine"));

    /** Arrival point inside the mine dimension (feet position). */
    public static final BlockPos ARRIVAL = new BlockPos(0, 63, 0);
    private static final int PAD_HALF = 2; // 5x5 pad
    private static final int PAD_Y = 62;
    private static final int CLEAR_Y_TO = 67;

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

    public ServerLevel mineLevel() {
        return server.getLevel(MINE_DIMENSION);
    }

    /** The single shared run every player resolves to. */
    public MineRunData resolveRunFor(ServerPlayer player) {
        return data;
    }

    /** Called on server start: detect a run left mid-initialization. */
    public void onServerStarted() {
        if (data.status() == RunStatus.INITIALIZING) {
            data.markFailed("initialization interrupted by server restart; run '/publicmine recover repair' to retry");
            LOGGER.warn("[publicmines] Mine run {} was left mid-initialization and was marked FAILED for safe retry.",
                    data.runId().isEmpty() ? "(unassigned)" : data.runId());
        }
        LOGGER.info("[publicmines] Loaded run state: status={} runId={} seed={} day={} ownedVolumes={}",
                data.status(), data.runId().isEmpty() ? "(none)" : data.runId(),
                data.seed(), data.dayId().isEmpty() ? "(none)" : data.dayId(), data.ownedVolumes().size());
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

        if (data.status() == RunStatus.READY) {
            steps.add(Step.ok("existing run", "already initialized as run " + data.runId() + "; nothing was changed"));
            steps.add(verifyArrivalPad());
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

        try {
            long sequence = data.runSequence() + 1;
            String dayId = LocalDate.now(PublicMineConfig.resetZoneId()).toString();
            String runId = dayId + "-" + sequence;
            long worldSeed = server.getWorldData().worldGenOptions().seed();
            long seed = worldSeed ^ (0x9E3779B97F4A7C15L * sequence);
            data.beginNewRun(runId, sequence, seed, dayId);
            steps.add(Step.ok("run record", "created run " + runId + " for day " + dayId + " (seed recorded)"));

            int radius = PublicMineConfig.mineRegionRadiusChunks();
            data.setBounds(-radius, -radius, radius - 1, radius - 1);
            BlockPos regionMin = new BlockPos(-radius * 16, mine.getMinBuildHeight(), -radius * 16);
            BlockPos regionMax = new BlockPos(radius * 16 - 1, mine.getMinBuildHeight() + mine.getHeight() - 1, radius * 16 - 1);
            data.addOwnedVolume(new OwnedVolume("active_region", MINE_DIMENSION, regionMin, regionMax));
            steps.add(Step.ok("ownership", "recorded active region of " + (2 * radius) + "x" + (2 * radius)
                    + " chunk columns centred on the arrival point"));

            BlockPos exit = server.overworld().getSharedSpawnPos();
            data.setSurfaceExit(exit);
            steps.add(Step.ok("surface exit", "recorded overworld spawn at " + describePos(exit)));

            steps.add(placeArrivalPad(mine));
            if (!steps.get(steps.size() - 1).ok()) {
                data.markFailed("arrival pad placement failed");
                return steps;
            }
            data.setMineArrival(ARRIVAL);
            data.addOwnedVolume(new OwnedVolume("arrival_pad", MINE_DIMENSION,
                    new BlockPos(ARRIVAL.getX() - PAD_HALF, PAD_Y, ARRIVAL.getZ() - PAD_HALF),
                    new BlockPos(ARRIVAL.getX() + PAD_HALF, CLEAR_Y_TO, ARRIVAL.getZ() + PAD_HALF)));
            data.markStatus(RunStatus.READY);
            steps.add(Step.ok("ready", "run " + data.runId()
                    + " is ready; players can enter with /publicmine enter"));
        } catch (Exception e) {
            data.markFailed("setup stopped unexpectedly: " + e.getMessage());
            steps.add(Step.fail("setup", "stopped unexpectedly: " + e.getMessage()
                    + "; nothing outside mine-owned volumes was touched; run '/publicmine recover repair' to retry"));
            LOGGER.warn("[publicmines] Mine initialization failed", e);
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
                        + " is not loaded; restart the server, then run '/publicmine setup validate' again"));

        checks.add(PublicMineConfig.isValidTimeZone(PublicMineConfig.resetTimeZoneName())
                ? Check.pass("reset timezone", "reset clock uses " + PublicMineConfig.resetZoneId()
                        + (PublicMineConfig.resetTimeZoneName().equalsIgnoreCase("server")
                                ? " (server default)" : ""))
                : Check.fail("reset timezone", "'" + PublicMineConfig.resetTimeZoneName()
                        + "' is not a valid time zone; fix with '/publicmine config set resetTimeZone Europe/Bucharest' or 'server'"));

        switch (data.status()) {
            case UNINITIALIZED -> checks.add(Check.warn("run state",
                    "no run exists yet; run '/publicmine setup initialize' to create the shared mine"));
            case INITIALIZING -> checks.add(Check.fail("run state",
                    "initialization is in progress; if the server was restarted, run '/publicmine recover repair'"));
            case FAILED -> checks.add(Check.fail("run state",
                    "last initialization failed: " + data.lastError()));
            case READY -> validateReadyRun(mine, checks);
        }

        String today = LocalDate.now(PublicMineConfig.resetZoneId()).toString();
        if (data.status() == RunStatus.READY && !data.dayId().equals(today)) {
            checks.add(Check.warn("run day",
                    "active run is for " + data.dayId() + " but today is " + today
                            + "; scheduled daily reset arrives in M7 and is not automatic yet"));
        }
        return checks;
    }

    private void validateReadyRun(ServerLevel mine, List<Check> checks) {
        if (data.runId().isEmpty() || data.seed() == 0) {
            checks.add(Check.fail("run record", "run is READY but run id or seed is missing; run '/publicmine recover repair'"));
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
            checks.add(Check.fail("arrival", "arrival pad is damaged; run '/publicmine recover repair'"));
        } else {
            checks.add(Check.pass("arrival", "arrival pad is safe at " + describePos(data.mineArrival())));
        }
        if (data.surfaceExit() == null) {
            checks.add(Check.warn("surface exit", "no surface exit recorded; '/publicmine leave' falls back to world spawn"));
        } else {
            checks.add(Check.pass("surface exit", "surface exit recorded at " + describePos(data.surfaceExit())));
        }
        checks.add(Check.pass("generation frontier", data.frontierRegions() + " generated region(s) recorded (procedural geometry arrives in M2)"));
        checks.add(Check.warn("reset scheduling",
                "effective reset is " + String.format("%02d:%02d", PublicMineConfig.resetHour(), PublicMineConfig.resetMinute())
                        + " " + PublicMineConfig.resetZoneId() + " once M7 scheduling ships; nothing resets automatically yet"));
    }

    /**
     * Safe recovery: inspects persisted state and repairs only mine-owned
     * state. Returns true when the run is usable afterwards.
     */
    public synchronized List<Step> recoverRepair() {
        List<Step> steps = new ArrayList<>();
        steps.add(Step.ok("inspect", "current status is " + data.status()
                + (data.runId().isEmpty() ? "" : " for run " + data.runId())));
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
                    "you are already inside the Public Mine");
        }
        if (!data.status().entryAllowed()) {
            return new EnterOutcome(EnterResult.NOT_READY,
                    data.status() == RunStatus.FAILED
                            ? "the mine is being repaired by staff; please try again later"
                            : "the mine is not open yet; staff can open it with '/publicmine setup initialize'");
        }
        ServerLevel mine = mineLevel();
        BlockPos target = data.mineArrival() != null ? data.mineArrival() : ARRIVAL;
        if (mine == null || !isArrivalSafe(mine, target)) {
            return new EnterOutcome(EnterResult.ARRIVAL_UNSAFE,
                    "the mine entrance is not safe right now; staff have been asked to check it");
        }
        data.setReturnPoint(player.getUUID(), new MineRunData.ReturnPoint(
                player.level().dimension(), player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot()));
        try {
            player.teleportTo(mine, target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.0F, 0.0F);
        } catch (Exception e) {
            data.clearReturnPoint(player.getUUID());
            return new EnterOutcome(EnterResult.FAILED,
                    "could not move you into the mine (" + e.getMessage() + "); staff can check '/publicmine status'");
        }
        return new EnterOutcome(EnterResult.ENTERED, "arrived in run " + data.runId());
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
            // Nothing safe to teleport to; keep the player where they are.
            return LeaveResult.FAILED;
        }
        try {
            player.teleportTo(destination, x, y, z, yaw, pitch);
        } catch (Exception e) {
            return LeaveResult.FAILED;
        }
        data.clearReturnPoint(player.getUUID());
        return LeaveResult.LEFT;
    }

    /** The nearest position where a player can stand without falling or
     * suffocating, searching down first (respawn-style) then a few blocks up. */
    private BlockPos findSafeLanding(ServerLevel level, BlockPos base) {
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
        return String.format("%02d:%02d %s", PublicMineConfig.resetHour(),
                PublicMineConfig.resetMinute(), PublicMineConfig.resetZoneId());
    }
}
