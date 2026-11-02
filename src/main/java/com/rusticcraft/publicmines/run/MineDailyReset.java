package com.rusticcraft.publicmines.run;

import com.mojang.logging.LogUtils;
import com.rusticcraft.publicmines.command.MineMessages;
import com.rusticcraft.publicmines.config.PublicMineConfig;
import com.rusticcraft.publicmines.generation.BoundedFlatLevelSource;
import com.rusticcraft.publicmines.generation.MineShellGeometry;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Scheduled daily reset of the shared public mine (M7).
 *
 * <p>At {@code resetHour:resetMinute} in {@code resetTimeZone} the scheduler
 * warns only mine occupants at the configured offsets, locks entry with
 * {@link RunStatus#RESETTING}, evacuates every occupant to a safe surface
 * position, restores every recorded mine-owned chunk column to the generated
 * flat baseline (bounded columns per tick), discards non-player entities and
 * loose drops inside owned volumes, and then creates exactly one new shared
 * run through the normal initialization path.
 *
 * <p>Idempotency is driven by two persisted fields: {@code resetPhase} (the
 * in-flight transaction) and {@code lastResetDue} (the last completed
 * scheduled occurrence). A restart or downtime always converges to exactly one
 * reset per scheduled occurrence: nothing happens while the phase is consumed,
 * and an in-flight phase resumes instead of restarting.
 */
public final class MineDailyReset {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Schedule evaluation frequency; cleanup work runs every tick. */
    private static final int SCHEDULE_CHECK_TICKS = 20;
    /** Maximum on-disk existence probes in flight at once. */
    private static final int EXISTENCE_PROBE_WINDOW = 256;
    /** Bounded chunk-column restore work per cleanup tick. */
    private static final int RESTORES_PER_TICK = 4;
    private static final long CLEANUP_BUDGET_NANOS = 15_000_000L;
    /** Ticks spent discarding late-loading entity sections before finalize. */
    private static final int SETTLE_TICKS = 15;
    /** Automatic cleanup/finalize retries before the reset halts for staff. */
    private static final int MAX_RESET_ATTEMPTS = 3;
    private static final long FAILURE_BACKOFF_TICKS = 100;

    private static MineDailyReset instance;

    private final MinecraftServer server;
    private final MineManager manager;

    /** Session-only cleanup bookkeeping. Resume rebuilds all of it from the
     * persisted phase/cursor, so nothing here needs to be saved. */
    private List<OwnedVolume> mineVolumes = List.of();
    private List<Long> resetColumns = List.of();
    private boolean columnsBuilt;
    private int probesIssued;
    private final TreeMap<Integer, CompletableFuture<Optional<CompoundTag>>> probes = new TreeMap<>();
    private int settleTicks;
    private long retryNotBeforeTick = Long.MIN_VALUE;
    private List<BlockState> flatLayers;
    private int shellRadius;
    private String warnedForInstant = "";
    private final Set<Integer> warningsSent = new HashSet<>();
    private long resetStartedNanos;
    private int blocksRestored;
    private int blockEntitiesRemoved;
    private int entitiesDiscarded;
    private int columnsMissing;
    private int evacuees;

    private MineDailyReset(MinecraftServer server) {
        this.server = server;
        this.manager = MineManager.get(server);
    }

    public static synchronized MineDailyReset get(MinecraftServer server) {
        if (instance == null || instance.server != server) {
            instance = new MineDailyReset(server);
        }
        return instance;
    }

    /** Called once the saved run has been loaded by the server. */
    public synchronized void onServerStarted() {
        MineRunData data = manager.data();
        if (data.resetInProgress()) {
            // The wall-clock start is session-only; a resumed transaction
            // measures elapsed time from this resume instead of the raw
            // nanoTime epoch.
            resetStartedNanos = System.nanoTime();
            LOGGER.info("[publicmines] Resuming daily reset phase {} for occurrence {} at column {}.",
                    data.resetPhase(), data.resetDue(), data.resetCursor());
        }
    }

    public void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer() == server) {
            tick();
        }
    }

    /** One scheduler tick. Visible for tests so phases can be driven
     * deterministically without waiting on the wall clock. */
    public synchronized void tick() {
        MineRunData data = manager.data();
        if (data.hasUnsupportedSchema()) {
            return;
        }
        if (data.resetInProgress()) {
            if (server.getTickCount() >= retryNotBeforeTick) {
                driveReset(data);
            }
            return;
        }
        if (server.getTickCount() % SCHEDULE_CHECK_TICKS != 0) {
            return;
        }
        evaluate(ZonedDateTime.now(PublicMineConfig.resetZoneId()));
    }

    /**
     * One scheduling evaluation at the given instant: deliver any due advance
     * warnings to mine occupants, then start the reset when the most recent
     * scheduled occurrence has not been consumed yet. Calling this with a
     * fixed instant is how tests exercise just-before/at/after behaviour.
     */
    public synchronized void evaluate(ZonedDateTime now) {
        MineRunData data = manager.data();
        if (data.hasUnsupportedSchema() || data.resetInProgress()) {
            return;
        }
        int hour = PublicMineConfig.resetHour();
        int minute = PublicMineConfig.resetMinute();
        maybeWarn(now, ResetSchedule.nextOccurrence(now, hour, minute));
        ZonedDateTime due = ResetSchedule.lastOccurrence(now, hour, minute);
        String dueKey = ResetSchedule.key(due);
        if (dueKey.equals(data.lastResetDue())) {
            return;
        }
        switch (data.status()) {
            case UNINITIALIZED -> {
                // No run exists to reset; consume the occurrence so a later
                // initialization is never surprised by a stale catch-up.
                data.noteResetDueConsumed(dueKey);
            }
            case READY, FAILED, RESETTING -> beginReset(dueKey);
            case INITIALIZING -> { /* setup in flight; evaluated again next tick */ }
        }
    }

    // ------------------------------------------------------------------
    // Advance warnings (mine occupants only)
    // ------------------------------------------------------------------

    private void maybeWarn(ZonedDateTime now, ZonedDateTime next) {
        String nextKey = ResetSchedule.key(next);
        if (!nextKey.equals(warnedForInstant)) {
            warnedForInstant = nextKey;
            warningsSent.clear();
        }
        List<Integer> due = ResetSchedule.warningsDue(
                PublicMineConfig.resetWarningMinutes(), warningsSent::contains, now, next);
        if (due.isEmpty()) {
            return;
        }
        warningsSent.addAll(due);
        long minutes = Math.max(1, ResetSchedule.minutesRemaining(now, next));
        MineMessages.notifyMineOccupants(server,
                "The Public Mine resets in about " + minutes + " minute" + (minutes == 1 ? "" : "s")
                        + " - use '/publicmine leave' to bank what you are carrying.");
        LOGGER.info("[publicmines] Warned {} mine occupant(s) about the {} reset ({} minute(s) remaining).",
                manager.occupants().size(), nextKey, minutes);
    }

    // ------------------------------------------------------------------
    // Reset transaction
    // ------------------------------------------------------------------

    private void beginReset(String dueKey) {
        MineRunData data = manager.data();
        resetSessionState();
        resetStartedNanos = System.nanoTime();
        data.markStatus(RunStatus.RESETTING); // lock entry before any cleanup
        data.beginDailyReset(dueKey);
        evacuees += evacuateOccupants();
        MineMessages.notifyAdmins(server, Commands.LEVEL_GAMEMASTERS,
                "Daily mine reset " + dueKey + " started; entry is locked until the new run is ready.");
        LOGGER.info("[publicmines] Daily reset {} started (occupants evacuated: {}).", dueKey, evacuees);
    }

    private void resetSessionState() {
        mineVolumes = List.of();
        resetColumns = List.of();
        columnsBuilt = false;
        probesIssued = 0;
        probes.clear();
        settleTicks = 0;
        retryNotBeforeTick = Long.MIN_VALUE;
        flatLayers = null;
        shellRadius = -1;
        resetStartedNanos = 0L;
        blocksRestored = 0;
        blockEntitiesRemoved = 0;
        entitiesDiscarded = 0;
        columnsMissing = 0;
        evacuees = 0;
    }

    private void ensureCleanupState(MineRunData data) {
        if (columnsBuilt) {
            return;
        }
        List<OwnedVolume> volumes = new ArrayList<>();
        for (OwnedVolume volume : data.ownedVolumes()) {
            if (volume.dimension().equals(MineManager.MINE_DIMENSION)) {
                volumes.add(volume);
            }
        }
        mineVolumes = volumes;
        LinkedHashSet<Long> columns = new LinkedHashSet<>();
        for (OwnedVolume volume : volumes) {
            int minChunkX = volume.min().getX() >> 4;
            int maxChunkX = volume.max().getX() >> 4;
            int minChunkZ = volume.min().getZ() >> 4;
            int maxChunkZ = volume.max().getZ() >> 4;
            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    columns.add(ChunkPos.asLong(cx, cz));
                }
            }
        }
        resetColumns = List.copyOf(columns);
        // The persisted cursor counts completed columns in this same order;
        // issue probes only for the columns still ahead of it.
        probesIssued = data.resetCursor();
        columnsBuilt = true;
    }

    private void driveReset(MineRunData data) {
        if (resetStartedNanos == 0L) {
            resetStartedNanos = System.nanoTime();
        }
        if (data.status() == RunStatus.FAILED && data.resetAttempts() >= MAX_RESET_ATTEMPTS) {
            // Halted for staff: '/publicmine reset retry' re-arms the attempts.
            // Checked here for BOTH phases so driveCleaning cannot re-mark the
            // run RESETTING and silently resume a cleanup staff halted.
            return;
        }
        switch (data.resetPhase()) {
            case CLEANING -> driveCleaning(data);
            case FINALIZING -> driveFinalizing(data);
            case NONE -> { /* raced with completion; nothing to do */ }
        }
    }

    private void driveCleaning(MineRunData data) {
        ServerLevel mine = manager.mineLevel();
        if (mine == null) {
            // The dimension cannot normally disappear; if it does, the phase
            // stays CLEANING and '/publicmine reset status' shows the stall.
            if (server.getTickCount() % 1200 == 0) {
                LOGGER.warn("[publicmines] Daily reset cleanup is waiting for dimension {} to load.",
                        MineManager.MINE_DIMENSION.location());
            }
            return;
        }
        // A reset owns the mine no matter which status a crash left behind.
        if (data.status() != RunStatus.RESETTING) {
            data.markStatus(RunStatus.RESETTING);
        }
        evacuees += evacuateOccupants();
        ensureCleanupState(data);
        if (!baselineResolved(mine)) {
            failReset(data, "the mine dimension does not use the bounded flat generator; "
                    + "cleanup cannot restore the baseline");
            return;
        }
        long deadline = System.nanoTime() + CLEANUP_BUDGET_NANOS;
        int restores = 0;
        int cursor = data.resetCursor();
        while (cursor < resetColumns.size() && restores < RESTORES_PER_TICK
                && System.nanoTime() < deadline) {
            issueProbes(mine, cursor);
            ColumnState state = resolveColumnState(mine, cursor);
            if (state == ColumnState.PENDING) {
                break;
            }
            if (state == ColumnState.EXISTS) {
                try {
                    restoreColumn(mine, cursor);
                    restores++;
                } catch (RuntimeException e) {
                    failReset(data, "chunk column " + describeColumn(cursor)
                            + " could not be restored: " + e.getMessage());
                    LOGGER.warn("[publicmines] Daily reset cleanup failed on column {}.",
                            describeColumn(cursor), e);
                    return;
                }
            } else {
                columnsMissing++;
            }
            cursor++;
            data.setResetCursor(cursor);
        }
        issueProbes(mine, cursor);
        entitiesDiscarded += discardOwnedEntities(mine);
        if (cursor >= resetColumns.size()) {
            settleTicks++;
            if (settleTicks >= SETTLE_TICKS) {
                entitiesDiscarded += discardOwnedEntities(mine);
                data.markResetFinalizing();
                LOGGER.info("[publicmines] Daily reset {} finished cleanup: {} columns checked ({} skipped, "
                                + "{} blocks restored, {} block entities removed, {} entities discarded).",
                        data.resetDue(), cursor, columnsMissing, blocksRestored,
                        blockEntitiesRemoved, entitiesDiscarded);
            }
        }
    }

    private void driveFinalizing(MineRunData data) {
        ServerLevel mine = manager.mineLevel();
        if (mine == null) {
            if (server.getTickCount() % 1200 == 0) {
                LOGGER.warn("[publicmines] Daily reset finalize is waiting for dimension {} to load.",
                        MineManager.MINE_DIMENSION.location());
            }
            return;
        }
        // Rebuild the session bookkeeping even though the cleanup loop is
        // done: after a restart into FINALIZING the owned-volume list is
        // empty, which would silently disable the entity purge below and
        // misreport the checked-column count in the completion summary.
        ensureCleanupState(data);
        entitiesDiscarded += discardOwnedEntities(mine);
        evacuees += evacuateOccupants();
        List<MineManager.Step> steps = manager.initialize();
        if (steps.stream().allMatch(MineManager.Step::ok)) {
            String consumed = data.resetDue();
            data.completeDailyReset(consumed);
            long elapsedMs = (System.nanoTime() - resetStartedNanos) / 1_000_000L;
            String summary = "daily reset " + consumed + " complete: new run " + data.runId()
                    + " (day " + data.dayId() + "), " + resetColumns.size() + " columns checked, "
                    + blocksRestored + " blocks restored, " + blockEntitiesRemoved + " block entities removed, "
                    + entitiesDiscarded + " entities discarded, " + evacuees + " player(s) evacuated, "
                    + elapsedMs + " ms";
            LOGGER.info("[publicmines] {}", summary);
            MineMessages.notifyAdmins(server, Commands.LEVEL_GAMEMASTERS,
                    "Daily mine reset complete: new run " + data.runId() + " is open.");
            resetSessionState();
        } else {
            int attempts = data.noteResetAttempt();
            String detail = steps.stream().filter(step -> !step.ok())
                    .map(step -> step.name() + ": " + step.detail())
                    .findFirst().orElse("unknown failure");
            if (attempts >= MAX_RESET_ATTEMPTS) {
                data.markFailed("daily reset finalize failed " + attempts + " times; last step: " + detail
                        + "; inspect '/publicmine reset status' then '/publicmine reset retry'");
                LOGGER.error("[publicmines] Daily reset halted after {} finalize attempts: {}",
                        attempts, detail);
                MineMessages.notifyAdmins(server, Commands.LEVEL_GAMEMASTERS,
                        "Daily mine reset is halted: " + detail
                                + " - see '/publicmine reset status' and retry with '/publicmine reset retry'.");
            } else {
                retryNotBeforeTick = server.getTickCount() + FAILURE_BACKOFF_TICKS;
                LOGGER.warn("[publicmines] Daily reset finalize attempt {} failed ({}); retrying.",
                        attempts, detail);
            }
        }
    }

    private void failReset(MineRunData data, String detail) {
        int attempts = data.noteResetAttempt();
        data.markFailed("daily reset " + data.resetPhase().name().toLowerCase()
                + " failed (attempt " + attempts + "): " + detail);
        if (attempts >= MAX_RESET_ATTEMPTS) {
            MineMessages.notifyAdmins(server, Commands.LEVEL_GAMEMASTERS,
                    "Daily mine reset is halted after " + attempts + " attempts: " + detail
                            + " - see '/publicmine reset status' and retry with '/publicmine reset retry'.");
        } else {
            retryNotBeforeTick = server.getTickCount() + FAILURE_BACKOFF_TICKS;
        }
    }

    // ------------------------------------------------------------------
    // Bounded owned-volume cleanup
    // ------------------------------------------------------------------

    private enum ColumnState { PENDING, EXISTS, MISSING }

    /** Issues on-disk existence probes ahead of the cleanup cursor. Chunk
     * regions are only checked, never generated: a column that does not exist
     * is already at baseline and is skipped for free. */
    private void issueProbes(ServerLevel mine, int fromCursor) {
        while (probesIssued < resetColumns.size()
                && probesIssued < fromCursor + EXISTENCE_PROBE_WINDOW
                && probes.size() < EXISTENCE_PROBE_WINDOW) {
            int index = probesIssued++;
            long packed = resetColumns.get(index);
            int cx = ChunkPos.getX(packed);
            int cz = ChunkPos.getZ(packed);
            if (mine.getChunkSource().hasChunk(cx, cz)) {
                probes.put(index, CompletableFuture.completedFuture(Optional.of(new CompoundTag())));
            } else {
                probes.put(index, mine.getChunkSource().chunkMap.read(new ChunkPos(cx, cz)));
            }
        }
    }

    private ColumnState resolveColumnState(ServerLevel mine, int cursor) {
        CompletableFuture<Optional<CompoundTag>> probe = probes.get(cursor);
        if (probe == null) {
            return ColumnState.PENDING;
        }
        if (!probe.isDone()) {
            return ColumnState.PENDING;
        }
        probes.remove(cursor);
        Optional<CompoundTag> result;
        try {
            result = probe.join();
        } catch (RuntimeException e) {
            // An unreadable region entry is treated as existing so the column
            // still gets a real load+restore attempt below.
            LOGGER.warn("[publicmines] Existence probe for {} failed; attempting restore anyway.",
                    describeColumn(cursor), e);
            return ColumnState.EXISTS;
        }
        return result.isPresent() ? ColumnState.EXISTS : ColumnState.MISSING;
    }

    private String describeColumn(int cursor) {
        if (cursor < 0 || cursor >= resetColumns.size()) {
            return "column index " + cursor;
        }
        long packed = resetColumns.get(cursor);
        return "[" + ChunkPos.getX(packed) + ", " + ChunkPos.getZ(packed) + "]";
    }

    /** Restores every block inside the recorded owned volumes of this chunk
     * column to the flat-generator baseline, and removes block entities there.
     * Nothing outside the volumes is touched. */
    private void restoreColumn(ServerLevel mine, int cursor) {
        long packed = resetColumns.get(cursor);
        int cx = ChunkPos.getX(packed);
        int cz = ChunkPos.getZ(packed);
        LevelChunk chunk = mine.getChunk(cx, cz);
        int minY = chunk.getMinBuildHeight();
        int topY = minY + chunk.getHeight() - 1;
        int changed = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (OwnedVolume volume : mineVolumes) {
            int x0 = Math.max(volume.min().getX(), cx * 16);
            int x1 = Math.min(volume.max().getX(), cx * 16 + 15);
            int y0 = Math.max(volume.min().getY(), minY);
            int y1 = Math.min(volume.max().getY(), topY);
            int z0 = Math.max(volume.min().getZ(), cz * 16);
            int z1 = Math.min(volume.max().getZ(), cz * 16 + 15);
            if (x0 > x1 || y0 > y1 || z0 > z1) {
                continue;
            }
            for (int y = y0; y <= y1; y++) {
                LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                if (section == null) {
                    continue;
                }
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        BlockState baseline = baselineAt(cx, cz, x, y, z, minY, topY);
                        if (section.getBlockState(x & 15, y & 15, z & 15).equals(baseline)) {
                            continue;
                        }
                        section.setBlockState(x & 15, y & 15, z & 15, baseline);
                        pos.set(x, y, z);
                        mine.getChunkSource().getLightEngine().checkBlock(pos);
                        changed++;
                    }
                }
            }
        }
        int removedEntities = 0;
        for (BlockPos blockEntityPos : List.copyOf(chunk.getBlockEntities().keySet())) {
            if (insideMineVolumes(blockEntityPos)) {
                chunk.removeBlockEntity(blockEntityPos);
                removedEntities++;
            }
        }
        if (changed > 0 || removedEntities > 0) {
            chunk.setUnsaved(true);
            Set<Heightmap.Types> heightmapTypes = new HashSet<>();
            for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
                heightmapTypes.add(entry.getKey());
            }
            if (!heightmapTypes.isEmpty()) {
                Heightmap.primeHeightmaps(chunk, heightmapTypes);
            }
        }
        blocksRestored += changed;
        blockEntitiesRemoved += removedEntities;
    }

    /** The block the bounded flat generator produces at this position:
     * the full-height bedrock shell and roof inside the shell boundary, and
     * the configured flat layers (or air) elsewhere. */
    private BlockState baselineAt(int cx, int cz, int x, int y, int z, int minY, int topY) {
        if (MineShellGeometry.inside(cx, cz, shellRadius)) {
            boolean side = (MineShellGeometry.isWestWall(cx, shellRadius) && x == cx * 16)
                    || (MineShellGeometry.isEastWall(cx, shellRadius) && x == cx * 16 + 15)
                    || (MineShellGeometry.isNorthWall(cz, shellRadius) && z == cz * 16)
                    || (MineShellGeometry.isSouthWall(cz, shellRadius) && z == cz * 16 + 15);
            if (side || y == topY) {
                return Blocks.BEDROCK.defaultBlockState();
            }
        }
        int layer = y - minY;
        if (layer >= 0 && layer < flatLayers.size()) {
            BlockState state = flatLayers.get(layer);
            if (state != null) {
                return state;
            }
        }
        return Blocks.AIR.defaultBlockState();
    }

    private boolean baselineResolved(ServerLevel mine) {
        if (flatLayers != null) {
            return true;
        }
        if (!(mine.getChunkSource().getGenerator() instanceof BoundedFlatLevelSource bounded)) {
            return false;
        }
        // getLayers() is the generator's own expanded per-height block list:
        // index (y - minBuildHeight) is exactly what the chunk generator
        // writes for that layer, with air above the last layer.
        flatLayers = List.copyOf(bounded.settings().getLayers());
        shellRadius = bounded.shellRadiusChunks();
        return true;
    }

    private boolean insideMineVolumes(BlockPos pos) {
        for (OwnedVolume volume : mineVolumes) {
            if (volume.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Evacuation and entity/drop purge
    // ------------------------------------------------------------------

    /** Moves every player inside the mine to their safe surface position.
     * Runs every cleanup tick so nobody can stand inside while terrain or
     * entities are being cleared. */
    private int evacuateOccupants() {
        int moved = 0;
        for (ServerPlayer player : manager.occupants()) {
            MineManager.LeaveResult result = manager.leave(player);
            if (result == MineManager.LeaveResult.LEFT) {
                moved++;
                player.sendSystemMessage(Component.literal(
                        "The Public Mine is resetting for a new day - you were moved to the surface "
                                + "with everything you carried."));
            } else {
                MineMessages.notifyAdmins(server, Commands.LEVEL_GAMEMASTERS,
                        "Could not evacuate " + player.getGameProfile().getName()
                                + " from the resetting mine (" + result + "); the reset continues "
                                + "and they must not re-enter.");
                LOGGER.warn("[publicmines] Evacuation of {} during daily reset returned {}.",
                        player.getGameProfile().getName(), result);
            }
        }
        return moved;
    }

    /** Discards every non-player entity inside the recorded mine-owned
     * volumes: tracked encounter mobs, loose drops, experience orbs, and any
     * other mine-owned entity. Runs every cleanup tick and during the settle
     * window so entities from just-loaded sections are caught too. */
    private int discardOwnedEntities(ServerLevel mine) {
        int discarded = 0;
        List<Entity> targets = new ArrayList<>();
        for (Entity entity : mine.getAllEntities()) {
            if (entity instanceof Player) {
                continue;
            }
            if (insideMineVolumes(entity.blockPosition())) {
                targets.add(entity);
            }
        }
        for (Entity entity : targets) {
            entity.discard();
            discarded++;
        }
        return discarded;
    }

    // ------------------------------------------------------------------
    // Admin surface
    // ------------------------------------------------------------------

    public enum RequestResult { STARTED, ALREADY_RUNNING, REJECTED }

    /** Operator-triggered reset. Uses a synthetic occurrence key so a manual
     * reset never consumes or collides with a real scheduled occurrence. */
    public synchronized RequestResult requestManualReset() {
        MineRunData data = manager.data();
        if (data.resetInProgress() || data.hasUnsupportedSchema()) {
            return data.resetInProgress() ? RequestResult.ALREADY_RUNNING : RequestResult.REJECTED;
        }
        if (data.status() == RunStatus.UNINITIALIZED || data.status() == RunStatus.INITIALIZING) {
            return RequestResult.REJECTED;
        }
        String manualKey = "manual:" + LocalDateTime.now(PublicMineConfig.resetZoneId());
        beginReset(manualKey);
        return RequestResult.STARTED;
    }

    /** Re-arms a halted reset; the scheduler continues on the next ticks. */
    public synchronized boolean retryReset() {
        MineRunData data = manager.data();
        if (!data.resetInProgress()) {
            return false;
        }
        data.clearResetAttempts();
        retryNotBeforeTick = Long.MIN_VALUE;
        LOGGER.info("[publicmines] Daily reset {} re-armed by staff at column {}/{}.",
                data.resetDue(), data.resetCursor(), resetColumns.size());
        return true;
    }

    /** Human-readable next reset for status output. */
    public String nextResetDescription() {
        ZonedDateTime now = ZonedDateTime.now(PublicMineConfig.resetZoneId());
        ZonedDateTime next = ResetSchedule.nextOccurrence(
                now, PublicMineConfig.resetHour(), PublicMineConfig.resetMinute());
        long minutes = ResetSchedule.minutesRemaining(now, next);
        return next.toLocalDate() + " " + String.format("%02d:%02d",
                PublicMineConfig.resetHour(), PublicMineConfig.resetMinute())
                + " " + PublicMineConfig.resetZoneId()
                + " (in " + (minutes / 60) + "h " + (minutes % 60) + "m)";
    }

    public List<String> statusLines() {
        MineRunData data = manager.data();
        List<String> lines = new ArrayList<>();
        lines.add("=== Daily reset status ===");
        lines.add("Schedule: " + manager.effectiveResetDescription()
                + " (next: " + nextResetDescription() + ")");
        List<Integer> warnings = PublicMineConfig.resetWarningMinutes();
        lines.add("Warnings: " + (warnings.isEmpty() ? "disabled"
                : warnings + " minute(s) before, mine occupants only"));
        lines.add("Last completed reset: " + (data.lastResetDue().isEmpty()
                ? "(none recorded)" : data.lastResetDue()));
        lines.add("Transaction: " + (data.resetInProgress()
                ? data.resetPhase() + " '" + data.resetDue() + "' - " + data.resetCursor()
                        + (columnsBuilt ? "/" + resetColumns.size() : "") + " columns, "
                        + data.resetAttempts() + " attempt(s), " + blocksRestored
                        + " blocks restored, " + entitiesDiscarded + " entities discarded"
                : "none"));
        if (data.status() == RunStatus.FAILED) {
            lines.add("Run state: FAILED - " + data.lastError());
        }
        return lines;
    }

    /** Effective schedule description reused by '/publicmine status'. */
    public String effectiveScheduleSummary() {
        return manager.effectiveResetDescription();
    }
}
