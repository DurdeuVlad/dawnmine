package com.dwurdy.dawnmine.gametest;

import com.dwurdy.dawnmine.run.MineManager;
import com.dwurdy.dawnmine.run.TeleporterData;
import com.dwurdy.dawnmine.dispatch.DispatchManager;
import com.dwurdy.dawnmine.teleporter.TeleporterManager;
import com.dwurdy.dawnmine.topology.HubLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Behavioral coverage for {@link TeleporterManager}: every assertion calls
 * the same physical-interaction path the Forge event listener uses
 * ({@code tryUseConsole}), never a shortcut into enter/leave directly.
 */
@GameTestHolder("dawnmine")
@PrefixGameTestTemplate(false)
public final class TeleporterGameTests {
    private TeleporterGameTests() { }

    /** Deterministic lodestone fixture: sturdy stone floor under the console
     * and every adjacent landing candidate, with guaranteed air at feet,
     * head, and headroom, before the lodestone itself is placed. */
    private static BlockPos prepareLodestone(GameTestHelper helper, ServerLevel level,
                                             BlockPos relative) {
        BlockPos pos = helper.absolutePos(relative);
        for (BlockPos feet : new BlockPos[] {pos, pos.north(), pos.south(),
                pos.east(), pos.west()}) {
            level.setBlockAndUpdate(feet.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(feet, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(feet.above(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(feet.above(2), Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(pos, Blocks.LODESTONE.defaultBlockState());
        helper.assertTrue(level.isLoaded(pos), "lodestone chunk not loaded at " + pos);
        helper.assertTrue(level.getBlockState(pos.north().below())
                        .isFaceSturdy(level, pos.north().below(),
                                net.minecraft.core.Direction.UP),
                "landing floor not sturdy at " + pos.north());
        return pos;
    }

    /** Canonical fixture accessor: the real dawnmine:mine level must be
     * loaded (a full dedicated server); GameTestServer never registers the
     * custom dimension, so the tests fail honestly instead of NPE. */
    private static ServerLevel mineLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer()
                .getLevel(MineManager.MINE_DIMENSION);
        helper.assertTrue(level != null,
                "fixture requires a full dedicated server loading dawnmine:mine, not GameTestServer");
        return level;
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void ordinaryBlockIsNeverAConsole(GameTestHelper helper) {
        ServerPlayer player = MockServerPlayers.inLevel(helper);
        ServerLevel level = player.serverLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        TeleporterManager.clearSessionState(player.getUUID());
        // Even repeated clicks on an ordinary block must never be cancelled
        // or consume a cooldown window.
        for (int i = 0; i < 3; i++) {
            helper.assertTrue(TeleporterManager.tryUseConsole(player, pos,
                            InteractionHand.MAIN_HAND)
                            == TeleporterManager.Result.NOT_A_CONSOLE,
                    "ordinary block was treated as a console");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void unboundLodestoneIsNotAConsole(GameTestHelper helper) {
        ServerPlayer player = MockServerPlayers.inLevel(helper);
        ServerLevel level = player.serverLevel();
        TeleporterData.get(player.getServer()).unbind();
        BlockPos pos = helper.absolutePos(new BlockPos(3, 2, 3));
        level.setBlockAndUpdate(pos, Blocks.LODESTONE.defaultBlockState());
        helper.assertTrue(TeleporterManager.tryUseConsole(player, pos,
                        InteractionHand.MAIN_HAND)
                        == TeleporterManager.Result.NOT_A_CONSOLE,
                "unbound overworld lodestone spoofed a console");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void spoofedPositionIsNotAConsole(GameTestHelper helper) {
        ServerPlayer player = MockServerPlayers.inLevel(helper);
        ServerLevel level = player.serverLevel();
        // A lodestone far out of reach, or at the right shape but wrong
        // dimension/position, must never trigger.
        BlockPos far = player.blockPosition().offset(40, 0, 0);
        level.setBlockAndUpdate(far, Blocks.LODESTONE.defaultBlockState());
        helper.assertTrue(TeleporterManager.tryUseConsole(player, far,
                        InteractionHand.MAIN_HAND)
                        == TeleporterManager.Result.NOT_A_CONSOLE,
                "out-of-reach block was treated as a console");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void offhandHubBarrelCancelsVanillaWithoutAction(GameTestHelper helper) {
        // Real console: the generated hub barrel position inside the actual
        // mine dimension, interacted through the physical event path.
        ServerLevel mine = mineLevel(helper);
        BlockPos pos = new BlockPos(HubLayout.DISPATCH_CONSOLE.x(),
                HubLayout.DISPATCH_CONSOLE.y(), HubLayout.DISPATCH_CONSOLE.z());
        mine.setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState());
        ServerPlayer player = MockServerPlayers.inLevel(helper, mine);
        player.teleportTo(mine, pos.getX() + 1.5, pos.getY(), pos.getZ() + 0.5, 0F, 0F);
        var menuBefore = player.containerMenu;
        PlayerInteractEvent.RightClickBlock event =
                new PlayerInteractEvent.RightClickBlock(player,
                        InteractionHand.OFF_HAND, pos,
                        net.minecraft.world.phys.BlockHitResult.miss(
                                new net.minecraft.world.phys.Vec3(
                                        pos.getX(), pos.getY(), pos.getZ()),
                                net.minecraft.core.Direction.UP, pos));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event);
        helper.assertTrue(event.isCanceled(),
                "offhand click on the hub barrel was not cancelled");
        helper.assertTrue(event.getCancellationResult()
                        == net.minecraft.world.InteractionResult.SUCCESS,
                "offhand cancellation result was " + event.getCancellationResult());
        helper.assertTrue(player.containerMenu == menuBefore,
                "offhand click opened a vanilla barrel menu");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void unsafeLandingRejectedAndHazardousBlocksFail(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos floor = helper.absolutePos(new BlockPos(5, 2, 5));
        // Solid floor + explicit air at feet and head = safe.
        level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        BlockPos feet = floor.above();
        level.setBlockAndUpdate(feet, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(feet.above(), Blocks.AIR.defaultBlockState());
        helper.assertTrue(TeleporterManager.safeLanding(level, feet),
                "clean floor rejected as unsafe");
        // Lava floor is hazardous.
        level.setBlockAndUpdate(floor, Blocks.LAVA.defaultBlockState());
        helper.assertTrue(!TeleporterManager.safeLanding(level, feet),
                "lava floor accepted as safe landing");
        // Magma floor is hazardous.
        level.setBlockAndUpdate(floor, Blocks.MAGMA_BLOCK.defaultBlockState());
        helper.assertTrue(!TeleporterManager.safeLanding(level, feet),
                "magma floor accepted as safe landing");
        // Fire at feet is hazardous.
        level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(feet, Blocks.FIRE.defaultBlockState());
        helper.assertTrue(!TeleporterManager.safeLanding(level, feet),
                "fire at feet accepted as safe landing");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void bindingLifecyclePersistsAndUnbindClearsOnlyData(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TeleporterData data = TeleporterData.get(level.getServer());
        data.unbind();
        BlockPos lodestone = prepareLodestone(helper, level, new BlockPos(7, 2, 7));
        BlockPos landing = TeleporterManager.deriveLanding(level, lodestone);
        helper.assertTrue(landing != null, "no safe landing next to lodestone at "
                + lodestone + " in " + level.dimension().location());
        helper.assertTrue(data.bind(lodestone, landing),
                "binding a valid lodestone was refused");
        helper.assertTrue(data.binding() != null
                        && data.binding().lodestone().equals(lodestone),
                "binding was not recorded");
        // Binding a second, different lodestone without unbinding is refused.
        BlockPos second = prepareLodestone(helper, level, new BlockPos(8, 2, 8));
        BlockPos landing2 = TeleporterManager.deriveLanding(level, second);
        helper.assertTrue(landing2 != null, "no safe landing next to second lodestone");
        helper.assertTrue(!data.bind(second, landing2),
                "rebinding without unbind was allowed");
        helper.assertTrue(data.binding().lodestone().equals(lodestone),
                "original binding was clobbered by refused rebind");
        // Explicit unbind then rebind is the accepted flow.
        data.unbind();
        helper.assertTrue(data.bind(second, landing2), "rebind after unbind refused");
        helper.assertTrue(data.binding().lodestone().equals(second),
                "rebind kept the old lodestone");
        // Unbind clears only the data - no world blocks are touched.
        data.unbind();
        helper.assertTrue(data.binding() == null, "unbind did not clear binding");
        helper.assertTrue(level.getBlockState(lodestone).is(Blocks.LODESTONE)
                        && level.getBlockState(second).is(Blocks.LODESTONE),
                "unbind mutated world blocks");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void hubReturnConsoleUsesSafeFallback(GameTestHelper helper) {
        // Physical path: lodestone at the generated console position inside
        // the real mine dimension, mainhand right-click through the handler.
        ServerLevel mine = mineLevel(helper);
        BlockPos pos = new BlockPos(HubLayout.TELEPORTER_CONSOLE.x(),
                HubLayout.TELEPORTER_CONSOLE.y(), HubLayout.TELEPORTER_CONSOLE.z());
        mine.setBlockAndUpdate(pos, Blocks.LODESTONE.defaultBlockState());
        ServerPlayer player = MockServerPlayers.inLevel(helper, mine);
        player.teleportTo(mine, pos.getX() + 0.5, pos.getY(), pos.getZ() + 1.5, 0F, 0F);
        TeleporterManager.clearSessionState(player.getUUID());
        TeleporterManager.Result result = TeleporterManager.tryUseConsole(
                player, pos, InteractionHand.MAIN_HAND);
        // A player without a recorded return must still escape through the
        // safe surface-exit/spawn fallback, including during a closed run.
        helper.assertTrue(result == TeleporterManager.Result.LEFT,
                "hub console with no recorded return returned " + result);
        helper.assertTrue(player.level().dimension()
                        .equals(net.minecraft.world.level.Level.OVERWORLD),
                "fallback left the player inside the mine");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 6000, batch = "dawnmine_hub_prepared")
    public static void surfaceConsoleEnterThenHubLeavePhysical(
            GameTestHelper helper) {
        PreparedFixture.awaitPrepared(helper,
                TeleporterGameTests::runSurfaceConsoleEnterThenHubLeavePhysical);
    }

    private static void runSurfaceConsoleEnterThenHubLeavePhysical(
            GameTestHelper helper) {
        // End-to-end physical round trip through the handler: bound surface
        // lodestone -> enter -> hub console -> leave, inventory unchanged.
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        ServerPlayer player = MockServerPlayers.inLevel(helper, overworld);
        MineManager manager = MineManager.get(player.getServer());
        TeleporterData data = TeleporterData.get(player.getServer());
        data.unbind();
        BlockPos lodestone = prepareLodestone(helper, overworld, new BlockPos(9, 2, 9));
        BlockPos landing = TeleporterManager.deriveLanding(overworld, lodestone);
        helper.assertTrue(landing != null, "roundtrip fixture has no safe landing");
        helper.assertTrue(data.bind(lodestone, landing), "fixture bind failed");
        player.getInventory().setItem(0,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 7));
        var held = player.getInventory().getItem(0).copy();
        player.teleportTo(overworld, lodestone.getX() + 0.5, lodestone.getY(),
                lodestone.getZ() + 1.5, 0F, 0F);
        TeleporterManager.clearSessionState(player.getUUID());
        helper.assertTrue(TeleporterManager.tryUseConsole(player, lodestone,
                        InteractionHand.MAIN_HAND) == TeleporterManager.Result.ENTERED,
                "surface console enter failed");
        helper.assertTrue(player.level().dimension()
                        .equals(MineManager.MINE_DIMENSION),
                "enter did not move the player into the mine");
        helper.assertTrue(player.blockPosition().equals(MineManager.ARRIVAL),
                "arrival is not inside the underground hub");
        helper.assertTrue(net.minecraft.world.item.ItemStack
                        .matches(held, player.getInventory().getItem(0)),
                "enter mutated the player's inventory");
        BlockPos hubConsole = new BlockPos(HubLayout.TELEPORTER_CONSOLE.x(),
                HubLayout.TELEPORTER_CONSOLE.y(), HubLayout.TELEPORTER_CONSOLE.z());
        player.teleportTo(player.serverLevel(), hubConsole.getX() + 0.5,
                hubConsole.getY(), hubConsole.getZ() + 1.5, 0F, 0F);
        helper.assertTrue(TeleporterManager.tryUseConsole(player, hubConsole,
                        InteractionHand.MAIN_HAND) == TeleporterManager.Result.ON_COOLDOWN,
                "immediate return ignored the console cooldown");
        helper.runAfterDelay(41, () -> {
            helper.assertTrue(TeleporterManager.tryUseConsole(player, hubConsole,
                            InteractionHand.MAIN_HAND) == TeleporterManager.Result.LEFT,
                    "hub console leave failed after cooldown expired");
            helper.assertTrue(player.level().dimension()
                            .equals(net.minecraft.world.level.Level.OVERWORLD),
                    "leave did not return the player to the overworld");
            helper.assertTrue(net.minecraft.world.item.ItemStack
                            .matches(held, player.getInventory().getItem(0)),
                    "leave mutated the player's inventory");
            helper.assertTrue(player.blockPosition().equals(lodestone.south()),
                    "round trip did not restore the recorded surface return point");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 6000, batch = "dawnmine_hub_prepared")
    public static void hubDispatchConsoleOpensPrivateLockers(GameTestHelper helper) {
        PreparedFixture.awaitPrepared(helper,
                TeleporterGameTests::runHubDispatchConsoleOpensPrivateLockers);
    }

    private static void runHubDispatchConsoleOpensPrivateLockers(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ServerLevel mine = mineLevel(helper);
        BlockPos console = new BlockPos(HubLayout.DISPATCH_CONSOLE.x(),
                HubLayout.DISPATCH_CONSOLE.y(), HubLayout.DISPATCH_CONSOLE.z());
        ServerPlayer alice = MockServerPlayers.inLevel(helper, server.overworld());
        ServerPlayer bob = MockServerPlayers.inLevel(helper, server.overworld());
        for (ServerPlayer player : new ServerPlayer[] {alice, bob}) {
            player.teleportTo(mine, console.getX() + 1.5,
                    console.getY(), console.getZ() + 0.5, 0F, 0F);
            TeleporterManager.clearSessionState(player.getUUID());
        }
        var aliceResult = TeleporterManager.tryUseConsole(alice, console, InteractionHand.MAIN_HAND);
        helper.assertTrue(aliceResult == TeleporterManager.Result.DISPATCH_OPENED,
                "hub console returned " + aliceResult + " (alice at "
                        + alice.blockPosition().toShortString() + " in "
                        + alice.serverLevel().dimension().location()
                        + ", authorized=" + DispatchManager.get(server).isAuthorizedAtStation(alice)
                        + ", runReady=" + (MineManager.get(server).data().status().name()) + ")");
        var firstMenu = alice.containerMenu;
        firstMenu.getSlot(0).set(new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.DIAMOND, 11));
        helper.assertTrue(TeleporterManager.tryUseConsole(bob, console,
                        InteractionHand.MAIN_HAND) == TeleporterManager.Result.DISPATCH_OPENED,
                "second player could not open their own private locker");
        helper.assertTrue(bob.containerMenu.getSlot(0).getItem().isEmpty(),
                "Bob sees Alice's private contents");
        helper.assertTrue(firstMenu.getSlot(0).getItem().getCount() == 11,
                "opening Bob's locker changed Alice's items");
        helper.assertTrue(DispatchManager.get(server).isAuthorizedAtStation(alice),
                "the hub is not an authorized Dispatch station");
        alice.closeContainer();
        bob.closeContainer();
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200, batch = "dawnmine_restart_probe")
    public static void restartProbeLeavesBinding(GameTestHelper helper) {
        // Part 1 of the restart-persistence probe: bind a lodestone and
        // deliberately leave it bound. Run this, restart the server, then
        // run restartProbeReadsBinding to prove the record survived.
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        TeleporterData data = TeleporterData.get(overworld.getServer());
        data.unbind();
        BlockPos lodestone = prepareLodestone(helper, overworld, new BlockPos(10, 2, 10));
        BlockPos landing = TeleporterManager.deriveLanding(overworld, lodestone);
        helper.assertTrue(landing != null, "restart probe has no safe landing");
        helper.assertTrue(data.bind(lodestone, landing), "restart probe bind refused");
        overworld.getDataStorage().save();
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200, batch = "dawnmine_restart_probe")
    public static void restartProbeReadsBinding(GameTestHelper helper) {
        // Part 2: after a server restart the binding left by
        // restartProbeLeavesBinding must still be readable. Unbinds so the
        // probe leaves no residue. Suite ordering relies on the probe name
        // order (leaves before reads): this test fails by design if it runs
        // without a prior bind, which is also the post-restart check.
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        TeleporterData data = TeleporterData.get(overworld.getServer());
        helper.assertTrue(data.binding() != null,
                "binding did not survive the restart - no binding recorded");
        data.unbind();
        helper.assertTrue(data.binding() == null, "probe unbind failed");
        helper.succeed();
    }
}
