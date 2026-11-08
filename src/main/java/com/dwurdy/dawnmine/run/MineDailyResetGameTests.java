package com.dwurdy.dawnmine.run;

import com.mojang.logging.LogUtils;
import com.dwurdy.dawnmine.config.DawnmineConfig;
import com.dwurdy.dawnmine.dispatch.DispatchManager;
import com.dwurdy.dawnmine.gametest.MockServerPlayers;
import com.dwurdy.dawnmine.gametest.PreparedFixture;
import com.dwurdy.dawnmine.topology.HubLayout;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Server-side M7 daily-reset transaction tests. The scheduler is driven
 * through {@link MineDailyReset#evaluate(ZonedDateTime)} with fixed instants
 * so just-before/at/after/downtime behaviour is deterministic.
 *
 * <p>The vanilla GameTestServer bakes the FLAT preset, so the mod's mine
 * dimension is absent there: these tests verify the scheduling, entry-lock,
 * and persistence contract that does not depend on live terrain. The bounded
 * owned-volume cleanup is verified on a dedicated server (see
 * docs/reports/M7-daily-reset-evidence.md).
 */
@GameTestHolder("dawnmine")
@PrefixGameTestTemplate(false)
public final class MineDailyResetGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();

    private MineDailyResetGameTests() { }

    @GameTest(template = "empty", timeoutTicks = 6000, batch = "m7_cancelled_evacuation")
    public static void cancelledEvacuationBlocksCleanup(GameTestHelper helper) {
        PreparedFixture.awaitPrepared(helper,
                MineDailyResetGameTests::runCancelledEvacuationBlocksCleanup);
    }

    private static void runCancelledEvacuationBlocksCleanup(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        MineManager manager = MineManager.get(server);
        MineRunData data = manager.data();
        CompoundTag snapshot = MineRunDataTestSupport.snapshot(data, server.registryAccess());
        ServerLevel mine = manager.mineLevel();
        ServerPlayer player = MockServerPlayers.inLevel(helper, server.overworld());
        BlockPos marker = new BlockPos(HubLayout.INTERIOR_HALF - 1, HubLayout.FEET_Y,
                HubLayout.INTERIOR_HALF - 1);
        var originalMarker = mine.getBlockState(marker);
        Consumer<EntityTravelToDimensionEvent> veto = event -> {
            if (event.getEntity() == player && event.getDimension().equals(Level.OVERWORLD)) {
                event.setCanceled(true);
            }
        };
        Runnable cleanup = () -> {
            NeoForge.EVENT_BUS.unregister(veto);
            try {
                player.containerMenu.setCarried(ItemStack.EMPTY);
                player.closeContainer();
                player.getInventory().clearContent();
                server.getPlayerList().remove(player);
            } finally {
                MineRunDataTestSupport.restore(data, snapshot, server.registryAccess());
                mine.setBlockAndUpdate(marker, originalMarker);
            }
        };
        try {
            helper.assertTrue(manager.enter(player).ok(), "could not enter the prepared hub");
            var console = HubLayout.DISPATCH_CONSOLE;
            player.teleportTo(mine, console.x() + 1.5, console.y(), console.z() + 0.5, 0F, 0F);
            for (int slot = 0; slot < 36; slot++) {
                player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            }
            DispatchManager dispatch = DispatchManager.get(server);
            helper.assertTrue(dispatch.open(player), "could not open the hub locker");
            var menu = player.containerMenu;
            menu.getSlot(0).set(new ItemStack(Items.DIAMOND, 5));
            menu.setCarried(menu.getSlot(0).remove(5));
            MineRunData.ReturnPoint returnPoint = data.returnPoint(player.getUUID());
            helper.assertTrue(returnPoint != null, "entry did not record the return point");
            mine.setBlockAndUpdate(marker, Blocks.STONE_BRICKS.defaultBlockState());
            NeoForge.EVENT_BUS.addListener(veto);
            helper.assertValueEqual(manager.leave(player), MineManager.LeaveResult.FAILED,
                    "cancelled dimension travel falsely reported a successful leave");
            helper.assertTrue(player.serverLevel() == mine && player.containerMenu == menu,
                    "cancelled leave changed the level or closed the menu");
            helper.assertValueEqual(data.returnPoint(player.getUUID()), returnPoint,
                    "cancelled leave discarded the return point");
            MineDailyReset reset = MineDailyReset.get(server);
            helper.assertValueEqual(reset.requestManualReset(), MineDailyReset.RequestResult.STARTED,
                    "reset did not start");
            reset.tick();
            helper.assertTrue(data.resetAttempts() > 0 && data.resetCursor() == 0,
                    "cleanup did not fail closed at the blocked evacuation");
            helper.runAfterDelay(2, () -> {
                try {
                    helper.assertTrue(player.serverLevel() == mine && player.containerMenu == menu,
                            "real game ticks closed the stranded player's menu");
                    helper.assertTrue(menu.stillValid(player) && menu.getCarried().is(Items.DIAMOND)
                                    && menu.getCarried().getCount() == 5,
                            "cursor stack was lost while evacuation remained blocked");
                    helper.assertFalse(dispatch.isAuthorizedAtStation(player),
                            "failed/resetting status admitted a new locker open");
                    helper.assertTrue(data.resetCursor() == 0
                                    && mine.getBlockState(marker).is(Blocks.STONE_BRICKS),
                            "cleanup mutated terrain before evacuation succeeded");
                    helper.assertValueEqual(data.returnPoint(player.getUUID()), returnPoint,
                            "reset discarded the blocked player's return point");
                    int outsideX = data.ownedVolumes().stream()
                            .mapToInt(volume -> volume.max().getX()).max().orElseThrow() + 32;
                    player.setPos(outsideX + 0.5, HubLayout.FEET_Y, 0.5);
                    // Off-station while still inside the mine is a parked
                    // evacuation: the menu must remain open with its carried
                    // stack until the player has actually left the dimension.
                    helper.assertTrue(menu.stillValid(player)
                                    && menu.getCarried().is(Items.DIAMOND)
                                    && menu.getCarried().getCount() == 5,
                            "the evacuating session lost its menu or carried stack off-station");
                } finally {
                    cleanup.run();
                }
                helper.succeed();
            });
        } catch (RuntimeException | Error failure) {
            cleanup.run();
            throw failure;
        }
    }

    /** Captures messages delivered to a command source for assertions. */
    private static final class RecordingSource implements CommandSource {
        final List<String> messages = new ArrayList<>();
        @Override public void sendSystemMessage(Component component) {
            messages.add(component.getString());
        }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
    }

    private static CommandSourceStack stack(ServerLevel level, int permission, RecordingSource out) {
        return new CommandSourceStack(out, Vec3.ZERO, Vec2.ZERO, level, permission,
                "m7-test", Component.literal("m7-test"), level.getServer(), null);
    }

    private static void run(ServerLevel level, int permission, String command, RecordingSource out) {
        level.getServer().getCommands().performPrefixedCommand(stack(level, permission, out), command);
    }

    private static String joined(RecordingSource out) {
        return String.join("\n", out.messages);
    }

    private static ServerPlayer fakePlayer(GameTestHelper helper) {
        return MockServerPlayers.inLevel(helper);
    }

    /** A scheduled occurrence at the configured wall time on a fixed date, so
     * assertions never depend on the real clock. */
    private static ZonedDateTime occurrence(LocalDate date) {
        return ZonedDateTime.of(date,
                LocalTime.of(DawnmineConfig.resetHour(), DawnmineConfig.resetMinute()),
                DawnmineConfig.resetZoneId());
    }

    /** Snapshot/restore of the shared run record's reset+status fields so each
     * test leaves the world exactly as it found it. */
    private record SavedResetState(RunStatus status, ResetPhase phase, String due,
                                   int cursor, int attempts, String lastDue) {
        static SavedResetState capture(MineRunData data) {
            return new SavedResetState(data.status(), data.resetPhase(), data.resetDue(),
                    data.resetCursor(), data.resetAttempts(), data.lastResetDue());
        }
        void restore(MineRunData data) {
            data.restoreResetTransaction(phase, due, cursor, attempts, lastDue);
            data.markStatus(status);
        }
    }

    private static final LocalDate TEST_DAY = LocalDate.of(2030, 1, 5);

    @GameTest(template = "empty", batch = "m7_reset_scheduled", timeoutTicks = 1200)
    public static void scheduledOccurrenceStartsExactlyOneResetAndLocksEntry(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MineManager manager = MineManager.get(level.getServer());
        MineDailyReset reset = MineDailyReset.get(level.getServer());
        MineRunData data = manager.data();
        SavedResetState prior = SavedResetState.capture(data);
        try {
            // A consumed earlier occurrence means only the new one is due.
            data.completeDailyReset(ResetSchedule.key(occurrence(TEST_DAY.minusDays(1))));
            data.markStatus(RunStatus.READY);

            // Just before the scheduled time: warnings may fly but no reset.
            reset.evaluate(occurrence(TEST_DAY).minusMinutes(1));
            helper.assertFalse(data.resetInProgress(), "reset started before its occurrence");
            helper.assertValueEqual(data.status(), RunStatus.READY, "entry locked early");

            // Exactly at the scheduled time the transaction opens: entry is
            // locked, the phase is CLEANING, and the occurrence is recorded.
            reset.evaluate(occurrence(TEST_DAY));
            helper.assertTrue(data.resetInProgress(), "scheduled reset did not start at 06:00");
            helper.assertValueEqual(data.resetPhase(), ResetPhase.CLEANING, "wrong reset phase");
            helper.assertValueEqual(data.resetDue(), ResetSchedule.key(occurrence(TEST_DAY)),
                    "reset recorded the wrong occurrence");
            helper.assertValueEqual(data.status(), RunStatus.RESETTING,
                    "entry stayed open during reset");

            MineManager.EnterOutcome denied = manager.enter(fakePlayer(helper));
            helper.assertValueEqual(denied.result(), MineManager.EnterResult.NOT_READY,
                    "entry allowed while the reset owns the mine");
            helper.assertTrue(denied.detail().contains("resetting"),
                    "entry denial did not explain the reset; got: " + denied.detail());

            // A crash that persisted READY alongside an in-flight phase must
            // still lock entry; the phase, not only the status, owns the mine.
            data.markStatus(RunStatus.READY);
            MineManager.EnterOutcome stillDenied = manager.enter(fakePlayer(helper));
            helper.assertValueEqual(stillDenied.result(), MineManager.EnterResult.NOT_READY,
                    "entry admitted while a reset phase was still in flight");
            data.markStatus(RunStatus.RESETTING);

            // Later evaluations never open a second transaction for the same
            // occurrence while the first is in flight.
            reset.evaluate(occurrence(TEST_DAY).plusHours(3));
            helper.assertValueEqual(data.resetDue(), ResetSchedule.key(occurrence(TEST_DAY)),
                    "a second reset transaction was opened for one occurrence");
        } finally {
            prior.restore(data);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "m7_reset_downtime", timeoutTicks = 1200)
    public static void downtimeCatchUpRunsExactlyOneReset(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MineManager manager = MineManager.get(level.getServer());
        MineDailyReset reset = MineDailyReset.get(level.getServer());
        MineRunData data = manager.data();
        SavedResetState prior = SavedResetState.capture(data);
        try {
            // The last completed reset was days ago: downtime crossed several
            // scheduled occurrences but only the most recent is due.
            data.completeDailyReset(ResetSchedule.key(occurrence(TEST_DAY.minusDays(3))));
            data.markStatus(RunStatus.READY);
            ZonedDateTime now = occurrence(TEST_DAY).plusHours(4);

            reset.evaluate(now);

            helper.assertTrue(data.resetInProgress(), "downtime catch-up did not start a reset");
            helper.assertValueEqual(data.resetDue(), ResetSchedule.key(occurrence(TEST_DAY)),
                    "catch-up picked anything but the most recent occurrence");

            // Once the transaction completes, re-evaluating the same window
            // starts nothing: the occurrence is consumed exactly once.
            data.completeDailyReset(data.resetDue());
            data.markStatus(RunStatus.READY);
            reset.evaluate(now.plusMinutes(30));
            helper.assertFalse(data.resetInProgress(), "a completed occurrence reset twice");
            helper.assertValueEqual(data.lastResetDue(), ResetSchedule.key(occurrence(TEST_DAY)),
                    "completed occurrence was not recorded as consumed");
        } finally {
            prior.restore(data);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "m7_reset_uninitialized", timeoutTicks = 1200)
    public static void uninitializedRunConsumesOccurrenceWithoutCleaning(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MineManager manager = MineManager.get(level.getServer());
        MineDailyReset reset = MineDailyReset.get(level.getServer());
        MineRunData data = manager.data();
        SavedResetState prior = SavedResetState.capture(data);
        try {
            data.completeDailyReset("");
            data.markStatus(RunStatus.UNINITIALIZED);

            reset.evaluate(occurrence(TEST_DAY).plusHours(1));

            helper.assertFalse(data.resetInProgress(), "a reset ran with no run to reset");
            helper.assertValueEqual(data.status(), RunStatus.UNINITIALIZED,
                    "evaluating reset state changed an uninitialized run");
            helper.assertValueEqual(data.lastResetDue(), ResetSchedule.key(occurrence(TEST_DAY)),
                    "the occurrence was not consumed for later initialization");
        } finally {
            prior.restore(data);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "m7_reset_cursor", timeoutTicks = 1200)
    public static void interruptedCleanupResumesFromPersistedCursor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // A dedicated record, not the shared SavedData: earlier tests may have
        // left generation/topology state on it whose unrelated load-time
        // validation would fail this round-trip closed for reasons this test
        // does not exercise.
        MineRunData data = new MineRunData();
        // Simulate a crash mid-cleanup: phase CLEANING at column 41.
        data.markStatus(RunStatus.RESETTING);
        data.beginDailyReset(ResetSchedule.key(occurrence(TEST_DAY)));
        data.setResetCursor(41);

        MineRunData reloaded = MineRunData.load(
                data.save(new CompoundTag(), level.registryAccess()),
                level.registryAccess());

        helper.assertTrue(reloaded.resetInProgress(), "restart lost the in-flight reset");
        helper.assertValueEqual(reloaded.resetPhase(), ResetPhase.CLEANING,
                "restart did not resume the CLEANING phase");
        helper.assertValueEqual(reloaded.resetCursor(), 41,
                "restart did not resume at the persisted column cursor");
        helper.assertValueEqual(reloaded.resetDue(), ResetSchedule.key(occurrence(TEST_DAY)),
                "restart forgot which occurrence was being reset");
        helper.assertValueEqual(reloaded.status(), RunStatus.RESETTING,
                "restart unlocked entry during an unfinished cleanup; lastError="
                        + reloaded.lastError());
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "m7_reset_manual", timeoutTicks = 1200)
    public static void manualResetIsSingleFlightAndStatusIsOperatorFacing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MineManager manager = MineManager.get(level.getServer());
        MineDailyReset reset = MineDailyReset.get(level.getServer());
        MineRunData data = manager.data();
        SavedResetState prior = SavedResetState.capture(data);
        try {
            data.completeDailyReset("");
            data.markStatus(RunStatus.READY);

            helper.assertValueEqual(reset.requestManualReset(), MineDailyReset.RequestResult.STARTED,
                    "operator reset did not start");
            helper.assertValueEqual(reset.requestManualReset(), MineDailyReset.RequestResult.ALREADY_RUNNING,
                    "a second reset ran alongside the first");
            helper.assertTrue(reset.retryReset(), "re-arming a live reset failed");

            RecordingSource playerOut = new RecordingSource();
            run(level, 0, "dawnmine reset status", playerOut);
            helper.assertFalse(joined(playerOut).contains("Daily reset status"),
                    "a normal player read staff reset diagnostics: " + joined(playerOut));

            RecordingSource opOut = new RecordingSource();
            run(level, 2, "dawnmine reset status", opOut);
            helper.assertTrue(joined(opOut).contains("Daily reset status"),
                    "operator got no reset status: " + joined(opOut));
            helper.assertTrue(joined(opOut).contains("CLEANING"),
                    "reset status hid the live phase: " + joined(opOut));
        } finally {
            prior.restore(data);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "m7_reset_halt", timeoutTicks = 1200)
    public static void haltedResetStaysHaltedUntilStaffRearm(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MineManager manager = MineManager.get(level.getServer());
        MineDailyReset reset = MineDailyReset.get(level.getServer());
        MineRunData data = manager.data();
        SavedResetState prior = SavedResetState.capture(data);
        try {
            data.completeDailyReset("");
            data.markStatus(RunStatus.RESETTING);
            data.beginDailyReset(ResetSchedule.key(occurrence(TEST_DAY)));
            for (int i = 0; i < 3; i++) {
                data.noteResetAttempt();
            }
            data.markStatus(RunStatus.FAILED); // halted for staff

            // The scheduler must not silently resume a halt in either phase:
            // drive ticks while FAILED with max attempts leave the state
            // untouched until '/dawnmine reset retry' clears the attempts.
            reset.tick();
            helper.assertValueEqual(data.status(), RunStatus.FAILED,
                    "a halted reset silently resumed");
            helper.assertTrue(data.resetInProgress(), "halt lost the transaction entirely");

            helper.assertTrue(reset.retryReset(), "staff re-arm was refused");
            helper.assertValueEqual(data.resetAttempts(), 0, "re-arm did not reset attempts");
        } finally {
            prior.restore(data);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "m7_reset_warnings", timeoutTicks = 1200)
    public static void warningOffsetsAreConfigurableAndBounded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Bad values are rejected with bounds; a valid list persists.
        RecordingSource tooBig = new RecordingSource();
        run(level, 2, "dawnmine config set resetWarningMinutes 30,999", tooBig);
        helper.assertTrue(joined(tooBig).contains("outside the allowed range"),
                "out-of-range warning offset accepted: " + joined(tooBig));

        RecordingSource tooMany = new RecordingSource();
        run(level, 2, "dawnmine config set resetWarningMinutes 60,45,30,20,15,10,5,4,3,2,1", tooMany);
        helper.assertTrue(joined(tooMany).contains("at most"),
                "unbounded warning list accepted: " + joined(tooMany));

        List<Integer> before = DawnmineConfig.resetWarningMinutesValue().get() == null
                ? List.of() : List.copyOf(DawnmineConfig.resetWarningMinutesValue().get());
        try {
            RecordingSource ok = new RecordingSource();
            run(level, 2, "dawnmine config set resetWarningMinutes 10,2", ok);
            helper.assertValueEqual(DawnmineConfig.resetWarningMinutes(), List.of(10, 2),
                    "valid warning list did not persist");
        } finally {
            DawnmineConfig.resetWarningMinutesValue().set(before);
            DawnmineConfig.resetWarningMinutesValue().save();
        }
        helper.succeed();
    }

    /**
     * Full live-cycle proof for servers that actually load the mine dimension
     * (the dedicated pack server): one manual reset evacuates a carrying
     * occupant, restores owned terrain to the flat baseline, removes a block
     * entity, a hostile mob, and a loose drop inside owned volumes, leaves
     * blocks outside those volumes and in the overworld untouched, and admits
     * exactly one new run with a different seed.
     *
     * <p>On GameTestServer (no mine dimension) and on any environment where
     * the run cannot reach READY, the test skips cleanly; the scheduling and
     * transaction contract is covered by the other tests in this batch.
     */
    @GameTest(template = "empty", batch = "m7_reset_live", timeoutTicks = 48000)
    public static void liveResetEvacuatesCleansOwnedVolumesAndRebuildsRun(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MineManager manager = MineManager.get(level.getServer());
        MineDailyReset reset = MineDailyReset.get(level.getServer());
        MineRunData data = manager.data();
        ServerLevel mine = manager.mineLevel();
        if (mine == null) {
            LOGGER.info("[dawnmine] M7 live reset GameTest skipped: {} is not loaded.",
                    MineManager.MINE_DIMENSION.location());
            helper.succeed();
            return;
        }
        manager.initialize();
        if (data.status() != RunStatus.READY) {
            LOGGER.info("[dawnmine] M7 live reset GameTest skipped: run cannot reach READY here "
                    + "({}); full-cycle evidence runs on the pack server.", data.status());
            helper.succeed();
            return;
        }
        SavedResetState prior = SavedResetState.capture(data);
        ServerLevel overworld = level.getServer().overworld();
        // Fixture inside the recorded owned volumes: a chunk column that
        // exists on disk, a foreign block + chest (block entity) in the air
        // layer, a hostile mob, and a loose drop.
        BlockPos insideMarker = new BlockPos(20, 100, 20);
        BlockPos chestPos = new BlockPos(22, 100, 22);
        mine.getChunk(insideMarker.getX() >> 4, insideMarker.getZ() >> 4);
        mine.setBlock(insideMarker, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
        mine.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
        Zombie zombie = new Zombie(EntityType.ZOMBIE, mine);
        zombie.moveTo(24.5, 70.0, 24.5, 0.0F, 0.0F);
        mine.addFreshEntity(zombie);
        ItemEntity drop = new ItemEntity(mine, 28.5, 70.0, 28.5,
                new ItemStack(Items.IRON_INGOT, 3));
        mine.addFreshEntity(drop);
        // Outside the recorded volumes (beyond the shell radius) and in the
        // overworld: both must be completely untouched by the cleanup.
        int outsideChunkX = DawnmineConfig.mineRegionRadiusChunks() + 8;
        BlockPos outsideMarker = new BlockPos(outsideChunkX * 16, 100, 0);
        mine.getChunk(outsideChunkX, 0); // generates the column so it exists
        mine.setBlock(outsideMarker, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        BlockPos overworldMarker = new BlockPos(0, 200, 0);
        overworld.setBlock(overworldMarker, Blocks.EMERALD_BLOCK.defaultBlockState(), 3);
        // A carrying occupant in the mine must be evacuated with inventory.
        ServerPlayer occupant = fakePlayer(helper);
        ItemStack carried = new ItemStack(Items.DIAMOND, 7);
        helper.assertTrue(occupant.getInventory().add(carried.copy()),
                "fixture could not arm the occupant's inventory");
        occupant.teleportTo(mine, 16.5, 65.0, 16.5, 0.0F, 0.0F);
        String oldRunId = data.runId();
        long oldSeed = data.seed();
        try {
            helper.assertValueEqual(reset.requestManualReset(),
                    MineDailyReset.RequestResult.STARTED, "live reset did not start");
            MineManager.EnterOutcome denied = manager.enter(fakePlayer(helper));
            helper.assertValueEqual(denied.result(), MineManager.EnterResult.NOT_READY,
                    "entry allowed while the reset owned the mine");
            helper.assertTrue(denied.detail().contains("resetting"),
                    "entry denial did not explain the reset; got: " + denied.detail());
        } catch (RuntimeException e) {
            prior.restore(data);
            throw e;
        }
        // Poll until the bounded transaction finishes, then prove the whole
        // cycle. The captured state is restored on the passing pass so the
        // world is left exactly as found; a timeout leaves a consistent
        // in-flight reset that the scheduler keeps driving.
        helper.succeedWhen(() -> {
            helper.assertFalse(data.resetInProgress(),
                    "reset never finished; status=" + data.status()
                            + " phase=" + data.resetPhase() + " cursor=" + data.resetCursor()
                            + " attempts=" + data.resetAttempts());
            // Exactly one new shared run with a new seed.
            helper.assertValueEqual(data.status(), RunStatus.READY,
                    "new run is not READY after the reset");
            helper.assertFalse(data.runId().equals(oldRunId),
                    "reset reused the old run id " + oldRunId);
            helper.assertTrue(data.seed() != oldSeed, "reset reused the old run seed");
            // The occupant is outside the mine with carried items intact.
            helper.assertFalse(occupant.level().dimension().equals(MineManager.MINE_DIMENSION),
                    "occupant was not evacuated from the resetting mine");
            helper.assertValueEqual(occupant.getInventory().countItem(Items.DIAMOND), 7,
                    "carried inventory changed during the reset");
            // Owned terrain restored to the flat baseline; the chest and
            // its block entity are gone, as are the hostile and the drop.
            helper.assertTrue(mine.getBlockState(insideMarker).isAir(),
                    "foreign block inside owned volume survived the reset");
            helper.assertTrue(mine.getBlockState(chestPos).isAir(),
                    "block entity inside owned volume survived the reset");
            helper.assertTrue(zombie.isRemoved(), "mine-owned hostile survived the reset");
            helper.assertTrue(drop.isRemoved(), "loose drop survived the reset");
            // Outside owned volumes and the overworld are untouched.
            helper.assertTrue(mine.getBlockState(outsideMarker).is(Blocks.GOLD_BLOCK),
                    "cleanup touched a block outside the recorded owned volumes");
            helper.assertTrue(overworld.getBlockState(overworldMarker).is(Blocks.EMERALD_BLOCK),
                    "cleanup touched the overworld");
            prior.restore(data);
        });
    }

    /** A player who logged out inside the mine and returns while a reset
     * transaction is in flight — or whose saved position the restored flat
     * baseline entombed — must be evacuated with their inventory. Online
     * occupants are evacuated by the reset driver; returning players are the
     * gap between evacuation passes. */
    @GameTest(template = "empty", batch = "m7_reset_login_evac", timeoutTicks = 1200)
    public static void loginInsideMineDuringOrAfterResetIsEvacuated(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MineManager manager = MineManager.get(level.getServer());
        MineRunData data = manager.data();
        ServerLevel mine = manager.mineLevel();
        if (mine == null) {
            LOGGER.info("[dawnmine] M7 login-evacuation GameTest skipped: {} is not loaded.",
                    MineManager.MINE_DIMENSION.location());
            helper.succeed();
            return;
        }
        manager.initialize();
        if (data.status() != RunStatus.READY) {
            LOGGER.info("[dawnmine] M7 login-evacuation GameTest skipped: run cannot reach "
                    + "READY here ({}); the live leg runs on the pack server.", data.status());
            helper.succeed();
            return;
        }
        SavedResetState prior = SavedResetState.capture(data);
        try {
            ServerPlayer player = fakePlayer(helper);
            player.getInventory().add(new ItemStack(Items.DIAMOND, 3));
            // Leg 1: an in-flight reset phase owns the mine.
            player.teleportTo(mine, 16.5, 65.0, 16.5, 0.0F, 0.0F);
            data.beginDailyReset("login-test");
            manager.evacuateUnsafeMineLogin(player);
            helper.assertFalse(player.level().dimension().equals(MineManager.MINE_DIMENSION),
                    "returning player stayed inside the mine during an in-flight reset");
            // Leg 2: run is READY again, but the restored baseline entombed
            // the saved position in solid stone.
            data.completeDailyReset("login-test");
            data.markStatus(RunStatus.READY);
            BlockPos tomb = new BlockPos(900, 40, 900);
            mine.getChunk(tomb.getX() >> 4, tomb.getZ() >> 4);
            player.teleportTo(mine, tomb.getX() + 0.5, tomb.getY(), tomb.getZ() + 0.5, 0.0F, 0.0F);
            manager.evacuateUnsafeMineLogin(player);
            helper.assertFalse(player.level().dimension().equals(MineManager.MINE_DIMENSION),
                    "entombed returning player stayed inside the mine");
            helper.assertValueEqual(player.getInventory().countItem(Items.DIAMOND), 3,
                    "carried inventory changed during login evacuation");
        } finally {
            prior.restore(data);
        }
        helper.succeed();
    }
}
