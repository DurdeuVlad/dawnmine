package com.dwurdy.dawnmine.gametest;

import com.dwurdy.dawnmine.config.DawnmineConfig;
import com.dwurdy.dawnmine.run.MineManager;
import com.dwurdy.dawnmine.run.MineRunData;
import com.dwurdy.dawnmine.run.MineRunDataTestSupport;
import com.dwurdy.dawnmine.run.RunStatus;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Server-side M1 lifecycle/admin tests. Registered only when the game test
 * system is enabled (dev gameTestServer run or -Dneoforge.enableGameTest).
 * They use server-side fake players as stand-ins for real sessions.
 *
 * <p>The vanilla GameTestServer bakes the FLAT preset, so mod datapack
 * dimensions are absent there; tests that need the real mine dimension
 * verify the safe-failure contract in that environment and the full path on
 * a normal dedicated server where the dimension loads (see /test runall
 * evidence in docs/reports/M1-dedicated-server-lifecycle-evidence.md).
 */
@GameTestHolder("dawnmine")
@PrefixGameTestTemplate(false)
public class MineLifecycleGameTests {

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
                "m1-test", Component.literal("m1-test"), level.getServer(), null);
    }

    private static void run(ServerLevel level, int permission, String command, RecordingSource out) {
        level.getServer().getCommands().performPrefixedCommand(stack(level, permission, out), command);
    }

    private static String joined(RecordingSource out) {
        return String.join("\n", out.messages);
    }

    /** EmbeddedChannel-backed mock player. FakePlayerFactory instances have no
     * netty channel, so cross-dimension teleports NPE on a live server. The
     * channel is pre-negotiated so pack mods' join payloads do not fault the
     * test — see {@link MockServerPlayers}. */
    private static ServerPlayer fakePlayer(GameTestHelper helper) {
        return MockServerPlayers.inLevel(helper);
    }

    // ------------------------------------------------------------------
    // Shared run
    // ------------------------------------------------------------------

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void twoPlayersResolveSameRun(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MineManager manager = MineManager.get(level.getServer());
        manager.initialize();
        ServerPlayer alice = fakePlayer(helper);
        ServerPlayer bob = fakePlayer(helper);
        MineRunData a = manager.resolveRunFor(alice);
        MineRunData b = manager.resolveRunFor(bob);
        helper.assertTrue(a == b, "players resolved to different run records");
        helper.assertValueEqual(a.runId(), b.runId(), "run id differs between two players");
        helper.assertTrue(a.seed() == b.seed(), "run seed differs between players");
        if (manager.mineLevel() != null) {
            helper.assertTrue(manager.data().status() == RunStatus.READY,
                    "setup did not reach READY: " + manager.data().status());
            helper.assertTrue(a.seed() != 0 && !a.runId().isEmpty(),
                    "READY run missing seed or id");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void setupIsIdempotent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MineManager manager = MineManager.get(level.getServer());
        manager.initialize();
        String firstRun = manager.data().runId();
        int volumes = manager.data().ownedVolumes().size();
        List<MineManager.Step> second = manager.initialize();
        helper.assertValueEqual(manager.data().runId(), firstRun, "re-init changed run id");
        helper.assertValueEqual(manager.data().ownedVolumes().size(), volumes,
                "re-init duplicated owned volumes");
        if (manager.mineLevel() != null) {
            helper.assertTrue(second.get(0).detail().contains("already initialized"),
                    "second init did not report 'already initialized'; got: " + second.get(0).detail());
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------
    // Entry boundary
    // ------------------------------------------------------------------

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void entryDeniedWhileNotReady(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MineManager manager = MineManager.get(level.getServer());
        manager.data().markStatus(RunStatus.UNINITIALIZED);
        MineManager.EnterOutcome outcome = manager.enter(fakePlayer(helper));
        helper.assertValueEqual(outcome.result(), MineManager.EnterResult.NOT_READY,
                "entry allowed before initialization");
        manager.initialize(); // restore for later tests
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void entryDeniedWhenArrivalUnsafe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MineManager manager = MineManager.get(level.getServer());
        manager.initialize();
        ServerLevel mine = manager.mineLevel();
        if (mine == null) {
            // dimension absent: initialization must fail safely and entry stay closed
            helper.assertValueEqual(manager.data().status(), RunStatus.FAILED,
                    "setup without dimension did not fail safely");
            helper.assertValueEqual(manager.enter(fakePlayer(helper)).result(),
                    MineManager.EnterResult.NOT_READY, "entry allowed without a ready mine");
        } else {
            // vandalize the pad, then confirm entry is refused
            mine.setBlock(MineManager.ARRIVAL.below(), Blocks.AIR.defaultBlockState(), 3);
            helper.assertValueEqual(manager.enter(fakePlayer(helper)).result(),
                    MineManager.EnterResult.ARRIVAL_UNSAFE, "entry allowed onto a broken pad");
            manager.recoverRepair();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void enterThenLeaveReturnsPlayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MineManager manager = MineManager.get(level.getServer());
        manager.initialize();
        ServerPlayer player = fakePlayer(helper);
        if (manager.mineLevel() == null) {
            helper.assertValueEqual(manager.enter(player).result(), MineManager.EnterResult.NOT_READY,
                    "entry allowed without a ready mine");
        } else {
            MineManager.EnterOutcome outcome = manager.enter(player);
            helper.assertTrue(outcome.ok(), "enter failed: " + outcome.detail());
            helper.assertTrue(player.level().dimension().equals(MineManager.MINE_DIMENSION),
                    "player not in mine dimension after enter");
            helper.assertTrue(manager.isOwned(player.level() instanceof ServerLevel sl ? sl : level,
                    player.blockPosition()), "arrival is outside recorded mine-owned volumes");
            helper.assertValueEqual(manager.leave(player), MineManager.LeaveResult.LEFT,
                    "leave did not move the player out");
            helper.assertFalse(player.level().dimension().equals(MineManager.MINE_DIMENSION),
                    "player still in mine after leave");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------
    // Recovery and persistence
    // ------------------------------------------------------------------

    @GameTest(template = "empty", timeoutTicks = 600, batch = "m1_recovery_cleanup")
    public static void partialInitRecoversSafely(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MineManager manager = MineManager.get(level.getServer());
        MineRunData data = manager.data();
        CompoundTag snapshot = MineRunDataTestSupport.snapshot(data, level.registryAccess());
        try {
            manager.initialize();
            String previousRun = data.runId();
            long previousSeed = data.seed();
            var previousVolumes = List.copyOf(data.ownedVolumes());
            var previousSegments = java.util.Set.copyOf(data.completedGenerationSegments());
            data.markStatus(RunStatus.INITIALIZING);
            manager.onServerStarted();
            helper.assertValueEqual(data.status(), RunStatus.FAILED,
                    "interrupted init was not flagged for recovery");
            List<MineManager.Step> steps = manager.recoverRepair();
            if (manager.mineLevel() == null) {
                helper.assertFalse(steps.stream().allMatch(MineManager.Step::ok),
                        "recovery claimed success without the mine dimension");
                helper.assertValueEqual(data.status(), RunStatus.FAILED,
                        "failed recovery did not stay in a safe state");
            } else {
                helper.assertTrue(steps.stream().allMatch(MineManager.Step::ok),
                        "recovery reported a failed step");
                // Either a fresh owned-terrain cleanup started (status RESETTING)
                // or recovery re-armed a reset already in flight (status stays
                // FAILED while it runs); both leave a persisted transaction that
                // locks entry until owned terrain is restored.
                helper.assertTrue(data.resetInProgress(), "cleanup transaction was not persisted");
                helper.assertValueEqual(data.runId(), previousRun, "recovery replaced the old run early");
                helper.assertTrue(data.seed() == previousSeed, "recovery replaced the seed before cleanup");
                helper.assertTrue(previousVolumes.equals(data.ownedVolumes()), "old ownership was discarded");
                helper.assertTrue(previousSegments.equals(new java.util.HashSet<>(data.completedGenerationSegments())),
                        "old generation receipts were discarded before cleanup");
                ServerPlayer player = fakePlayer(helper);
                try {
                    helper.assertValueEqual(manager.enter(player).result(), MineManager.EnterResult.NOT_READY,
                            "entry opened before failed terrain was cleaned");
                } finally {
                    level.getServer().getPlayerList().remove(player);
                }
            }
        } finally {
            // No cleanup tick runs inside this synchronous test callback.
            MineRunDataTestSupport.restore(data, snapshot, level.registryAccess());
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void runStateSurvivesSerialization(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MineManager manager = MineManager.get(level.getServer());
        manager.initialize();
        MineRunData original = manager.data();
        HolderLookup.Provider registries = level.registryAccess();
        CompoundTag saved = original.save(new CompoundTag(), registries);
        MineRunData restored = MineRunData.load(saved, registries);
        helper.assertValueEqual(restored.runId(), original.runId(), "runId lost in save/load");
        helper.assertTrue(restored.seed() == original.seed(), "seed lost in save/load");
        helper.assertValueEqual(restored.status(), original.status(), "status lost in save/load");
        helper.assertValueEqual(restored.ownedVolumes().size(), original.ownedVolumes().size(),
                "owned volumes lost in save/load");
        helper.assertValueEqual(restored.dayId(), original.dayId(), "dayId lost in save/load");
        helper.succeed();
    }

    // ------------------------------------------------------------------
    // Permissions and config
    // ------------------------------------------------------------------

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void permissionMatrixIsEnforced(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // players (0) and moderators (1) cannot run admin mutations or views
        for (int denied : new int[] {0, 1}) {
            RecordingSource cfg = new RecordingSource();
            run(level, denied, "dawnmine config get", cfg);
            helper.assertFalse(joined(cfg).contains("netherrackEnabled"),
                    "level " + denied + " read staff config: " + joined(cfg));
            RecordingSource setup = new RecordingSource();
            run(level, denied, "dawnmine setup initialize", setup);
            helper.assertFalse(joined(setup).contains("[ok]"),
                    "level " + denied + " ran setup initialize: " + joined(setup));
        }
        RecordingSource deniedStatus = new RecordingSource();
        run(level, 0, "dawnmine status", deniedStatus);
        helper.assertFalse(joined(deniedStatus).contains("seed:"),
                "player status leaked staff diagnostics: " + joined(deniedStatus));
        // operators (2+) and console (4) can
        RecordingSource opOut = new RecordingSource();
        run(level, 2, "dawnmine config get", opOut);
        helper.assertTrue(joined(opOut).contains("netherrackEnabled"),
                "operator could not read config: " + joined(opOut));
        RecordingSource consoleOut = new RecordingSource();
        run(level, 4, "dawnmine setup validate", consoleOut);
        helper.assertFalse(consoleOut.messages.isEmpty(), "console got no validation output");
        // read-only player commands still work for everyone
        RecordingSource helpOut = new RecordingSource();
        run(level, 0, "dawnmine help", helpOut);
        helper.assertTrue(joined(helpOut).contains("enter"), "player help missing");
        helper.assertFalse(joined(helpOut).contains("config set"),
                "player help leaked admin commands");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void configValidationAndPersistence(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int beforeInterval = DawnmineConfig.netherrackColumnIntervalValue().get();
        // unsafe Netherrack rates are rejected with bounds + example
        RecordingSource low = new RecordingSource();
        run(level, 2, "dawnmine config set netherrackColumnInterval 30", low);
        helper.assertTrue(joined(low).contains("256") && joined(low).contains("65536"),
                "unsafe netherrack interval not rejected with bounds; got: " + joined(low));
        RecordingSource high = new RecordingSource();
        run(level, 2, "dawnmine config set netherrackColumnInterval 70000", high);
        helper.assertTrue(joined(high).contains("outside the allowed range"),
                "over-max interval accepted; got: " + joined(high));
        helper.assertValueEqual(DawnmineConfig.netherrackColumnIntervalValue().get(), beforeInterval,
                "rejected value overwrote the stored value");
        // unknown keys rejected with the valid list
        RecordingSource unknown = new RecordingSource();
        run(level, 2, "dawnmine config set bogusKey 5", unknown);
        helper.assertTrue(joined(unknown).contains("Unknown setting"),
                "unknown config key accepted; got: " + joined(unknown));
        // valid change persists into the backing config value
        RecordingSource ok = new RecordingSource();
        run(level, 2, "dawnmine config set netherrackColumnInterval 512", ok);
        helper.assertTrue(joined(ok).contains("512"), "valid change not confirmed; got: " + joined(ok));
        helper.assertValueEqual(DawnmineConfig.netherrackColumnIntervalValue().get(), 512,
                "config set did not persist to the config value");
        // restore the pre-test value for repeatability
        DawnmineConfig.netherrackColumnIntervalValue().set(beforeInterval);
        DawnmineConfig.netherrackColumnIntervalValue().save();
        // invalid types rejected
        RecordingSource badBool = new RecordingSource();
        run(level, 2, "dawnmine config set netherrackEnabled maybe", badBool);
        helper.assertTrue(joined(badBool).contains("true or false"),
                "non-boolean accepted for boolean key; got: " + joined(badBool));
        RecordingSource badZone = new RecordingSource();
        run(level, 2, "dawnmine config set resetTimeZone Not/AZone", badZone);
        helper.assertTrue(joined(badZone).contains("not a known time zone"),
                "invalid time zone accepted; got: " + joined(badZone));
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void netherrackDefaultsOffAndBounded(GameTestHelper helper) {
        helper.assertFalse(DawnmineConfig.netherrackEnabledValue().get(),
                "netherrack is enabled by default");
        helper.assertValueEqual(DawnmineConfig.netherrackColumnIntervalValue().getDefault(),
                DawnmineConfig.NETHERRACK_INTERVAL_MIN,
                "netherrack default interval is not the 256-column floor");
        helper.succeed();
    }
}

