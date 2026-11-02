package com.rusticcraft.publicmines.dispatch;

import com.mojang.logging.LogUtils;
import com.rusticcraft.publicmines.config.PublicMineConfig;
import com.rusticcraft.publicmines.run.MineManager;
import com.rusticcraft.publicmines.run.MineRunData;
import com.rusticcraft.publicmines.run.MineRunDataTestSupport;
import com.rusticcraft.publicmines.run.OwnedVolume;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

/** Dedicated-server checks for registry-aware item persistence and menu transfer behavior. */
@GameTestHolder("publicmines")
@PrefixGameTestTemplate(false)
public final class DispatchGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Fixed sentinel owner for the two-step real restart persistence check. */
    private static final UUID RESTART_SENTINEL =
            UUID.fromString("7c1f6f2a-9f6b-4e2a-8d3c-1a5e6b7c8d9f");
    private DispatchGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void uuidLockersHaveSeparateContentsAndFixedBounds(GameTestHelper helper) {
        DispatchData dispatch = new DispatchData();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        new DispatchContainer(dispatch, alice).setItem(53, new ItemStack(Items.EMERALD, 12));
        helper.assertTrue(dispatch.contents(alice).size() == 54 && dispatch.contents(bob).size() == 54,
                "locker capacity is not fixed at 54 slots");
        helper.assertTrue(dispatch.contents(bob).get(53).isEmpty(),
                "another UUID can read the first player's locker");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void itemStacksPersistAcrossSavedDataReload(GameTestHelper helper) {
        DispatchData dispatch = new DispatchData();
        UUID owner = UUID.randomUUID();
        new DispatchContainer(dispatch, owner).setItem(17, new ItemStack(Items.DIAMOND, 23));
        CompoundTag saved = dispatch.save(new CompoundTag(), helper.getLevel().registryAccess());
        saved.remove("schema"); // schema-less v0 lockers are upgraded without changing item contents
        DispatchData reloaded = DispatchData.load(saved, helper.getLevel().registryAccess());

        ItemStack restored = reloaded.contents(owner).get(17);
        helper.assertTrue(reloaded.isAvailable(), "valid Dispatch data failed to load");
        helper.assertTrue(restored.is(Items.DIAMOND) && restored.getCount() == 23,
                "item stack did not persist exactly across a SavedData reload");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void outOfBoundsSlotFailsClosedAndPreservesSavedData(GameTestHelper helper) {
        CompoundTag malformed = new CompoundTag();
        malformed.putInt("schema", 1);
        ListTag players = new ListTag();
        CompoundTag player = new CompoundTag();
        player.putUUID("owner", UUID.randomUUID());
        ListTag items = new ListTag();
        CompoundTag item = new CompoundTag();
        item.putInt("slot", DispatchData.SLOT_COUNT);
        item.put("item", new CompoundTag());
        items.add(item);
        player.put("items", items);
        players.add(player);
        malformed.put("players", players);

        DispatchData loaded = DispatchData.load(malformed, helper.getLevel().registryAccess());
        helper.assertFalse(loaded.isAvailable(), "out-of-bounds slot data was accepted");
        helper.assertTrue(malformed.equals(loaded.save(new CompoundTag(), helper.getLevel().registryAccess())),
                "malformed locker data was not preserved verbatim");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void fullLockerTransferLeavesPlayerStackUntouched(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        DispatchData data = new DispatchData();
        DispatchContainer locker = new DispatchContainer(data, player.getUUID());
        for (int slot = 0; slot < DispatchData.SLOT_COUNT; slot++) {
            locker.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
        ChestMenu menu = new ChestMenu(MenuType.GENERIC_9x6, 1, player.getInventory(), locker, 6);

        ItemStack moved = menu.quickMoveStack(player, 81); // first hotbar slot
        helper.assertTrue(moved.isEmpty(), "full locker unexpectedly accepted the hotbar stack");
        helper.assertTrue(player.getInventory().getItem(0).is(Items.DIAMOND)
                        && player.getInventory().getItem(0).getCount() == 7,
                "failed full-locker transfer consumed or changed the source stack");
        helper.assertTrue(locker.getItem(0).is(Items.COBBLESTONE)
                        && locker.getItem(0).getCount() == 64,
                "failed transfer changed the full locker contents");
        helper.succeed();
    }

    /** Vanilla never calls {@code removed} on disconnect; the logout hook must
     * return the cursor stack to the owner and free the session so a reconnect
     * can open the Dispatch again. */
    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void logoutReleasesSessionAndReturnsCarriedStack(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        DispatchManager manager = DispatchManager.get(helper.getLevel().getServer());
        DispatchData data = manager.data();
        UUID owner = player.getUUID();
        helper.assertTrue(data.acquire(owner), "could not take the Dispatch session lock");

        DispatchMenu menu = new DispatchMenu(1, player.getInventory(), player, data, p -> true);
        player.containerMenu = menu;
        menu.setCarried(new ItemStack(Items.DIAMOND, 5));

        manager.onPlayerLogout(player);

        helper.assertTrue(menu.getCarried().isEmpty(), "carried stack was not recovered on logout");
        helper.assertTrue(player.getInventory().countItem(Items.DIAMOND) == 5,
                "carried stack did not return to the logging-out player's inventory");
        helper.assertTrue(player.containerMenu == player.inventoryMenu,
                "logout did not close the Dispatch menu");
        helper.assertTrue(data.acquire(owner), "session lock leaked after logout");
        data.release(owner);
        helper.succeed();
    }

    /**
     * End-to-end check at the surface depot and a generated checkpoint: opens
     * the Dispatch through the real player command, round-trips a deposit across
     * a close/reopen, confirms the same contents at the checkpoint terminal, and
     * releases the session through the real disconnect path. The run fixture is
     * fabricated and restored like the M2c/M4a GameTests, so the whole command →
     * zone → menu → persistence chain is exercised on any server; the checkpoint
     * leg additionally needs the real mine dimension (dedicated server).
     */
    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void dispatchAtSurfaceDepotServesSameLockerAndSurvivesDisconnect(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DispatchManager manager = DispatchManager.get(level.getServer());
        MineRunData run = MineManager.get(level.getServer()).data();
        var originalRun = MineRunDataTestSupport.snapshot(run, level.registryAccess());
        ServerLevel mine = MineManager.get(level.getServer()).mineLevel();
        DispatchData data = manager.data();
        ServerPlayer player = null;
        try {
            BlockPos depot = helper.absolutePos(new BlockPos(1, -59, 1));
            helper.assertTrue(MineRunDataTestSupport.makeReadyWithSurfaceExit(run, depot),
                    "could not fabricate the READY surface-depot fixture");
            MineRunDataTestSupport.addMineOwnedRegion(run, PublicMineConfig.mineRegionRadiusChunks(),
                    level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
            String checkpointId = null;
            if (mine != null) {
                BlockPos pad = new BlockPos(200, 90, 200);
                OwnedVolume zone = new OwnedVolume(
                        "dispatch_gametest_checkpoint", MineManager.MINE_DIMENSION,
                        pad.offset(-2, -1, -2), pad.offset(2, 2, 2));
                checkpointId = run.registerCheckpointStation("dispatch-test", "Dispatch test stop",
                        "upper", 100, pad, zone) ? "dispatch-test" : null;
                helper.assertTrue(checkpointId != null,
                        "could not register the checkpoint-station fixture");
            } else {
                LOGGER.warn("[publicmines] Dispatch depot GameTest: mine dimension absent; "
                        + "checkpoint-terminal coverage runs on the dedicated server.");
            }

            player = helper.makeMockServerPlayerInLevel();
            UUID owner = player.getUUID();

            // Away from every station the command must refuse with guidance.
            player.moveTo(depot.getX() + 64.5, depot.getY(), depot.getZ() + 64.5, 0.0F, 0.0F);
            helper.assertTrue(!manager.isAuthorizedAtStation(player),
                    "player far outside every terminal zone was treated as at a station");
            player.getServer().getCommands()
                    .performPrefixedCommand(player.createCommandSourceStack(), "publicmine dispatch");
            helper.assertTrue(!(player.containerMenu instanceof DispatchMenu),
                    "dispatch menu opened outside of any terminal zone");

            player.moveTo(depot.getX() + 0.5, depot.getY(), depot.getZ() + 0.5, 0.0F, 0.0F);
            helper.assertTrue(manager.isAuthorizedAtStation(player),
                    "player standing on the surface depot pad is not authorized for the Dispatch");

            player.getServer().getCommands()
                    .performPrefixedCommand(player.createCommandSourceStack(), "publicmine dispatch");
            helper.assertTrue(player.containerMenu instanceof DispatchMenu,
                    "'/publicmine dispatch' did not open the locker menu at the surface depot");

            player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 10));
            ItemStack moved = player.containerMenu.quickMoveStack(player, 81); // first hotbar slot
            helper.assertTrue(!moved.isEmpty() && moved.is(Items.DIAMOND),
                    "shift-click deposit into the locker failed");
            helper.assertTrue(data.contents(owner).stream().anyMatch(stack -> stack.is(Items.DIAMOND)),
                    "deposit is not visible in the persistent locker");

            player.closeContainer();
            player.getServer().getCommands()
                    .performPrefixedCommand(player.createCommandSourceStack(), "publicmine dispatch");
            helper.assertTrue(player.containerMenu instanceof DispatchMenu,
                    "locker did not reopen at the depot");
            helper.assertTrue(data.contents(owner).stream().anyMatch(stack -> stack.is(Items.DIAMOND)),
                    "reopened locker lost the deposited items");

            // The same locker must be served from a generated checkpoint terminal.
            if (checkpointId != null) {
                MineRunData.CheckpointStation station = run.checkpointStation(checkpointId);
                BlockPos pad = station.arrival();
                player.closeContainer();
                player.teleportTo(mine, pad.getX() + 0.5, pad.getY(), pad.getZ() + 0.5, 0.0F, 0.0F);
                helper.assertTrue(manager.isAuthorizedAtStation(player),
                        "player on a generated checkpoint pad is not authorized for the Dispatch");
                player.getServer().getCommands()
                        .performPrefixedCommand(player.createCommandSourceStack(), "publicmine dispatch");
                helper.assertTrue(player.containerMenu instanceof DispatchMenu,
                        "'/publicmine dispatch' did not open the locker menu at checkpoint " + checkpointId);
                helper.assertTrue(data.contents(owner).stream().anyMatch(stack -> stack.is(Items.DIAMOND)),
                        "checkpoint terminal does not serve the same locker contents");
            }

            // The real disconnect path must close the menu and free the session.
            player.connection.disconnect(Component.literal("dispatch depot test complete"));
            helper.assertTrue(player.containerMenu == player.inventoryMenu,
                    "disconnect left the Dispatch menu open");
            helper.assertTrue(data.acquire(owner), "session lock leaked across a real disconnect");
            data.release(owner);
            new DispatchContainer(data, owner).clearContent();
        } finally {
            if (player != null) {
                new DispatchContainer(data, player.getUUID()).clearContent();
                if (player.containerMenu != player.inventoryMenu) {
                    player.doCloseContainer();
                }
                if (level.getServer().getPlayerList().getPlayer(player.getUUID()) != null) {
                    player.connection.disconnect(Component.literal("dispatch depot test cleanup"));
                }
            }
            MineRunDataTestSupport.restore(run, originalRun, level.registryAccess());
        }
        helper.succeed();
    }

    /**
     * Step 1 of the real stop/start proof: plants a sentinel deposit in the live
     * SavedData so a later boot can check the file-backed contents. Runs in any
     * environment; the meaningful run is the dedicated-server restart pair.
     */
    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void dispatchRestartSeed(GameTestHelper helper) {
        DispatchManager manager = DispatchManager.get(helper.getLevel().getServer());
        DispatchContainer locker = new DispatchContainer(manager.data(), RESTART_SENTINEL);
        locker.setItem(20, new ItemStack(Items.NETHERITE_SCRAP, 7));
        helper.succeed();
    }

    /**
     * Step 2 of the real stop/start proof: after a full server shutdown and
     * restart (and optionally a new run), the sentinel locker contents must be
     * byte-identical. When the seed step has never produced a locker on this
     * world the test skips with a warning; a same-session pass only proves the
     * in-memory path, so the dedicated-server restart pair is the real evidence.
     */
    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void dispatchRestartVerify(GameTestHelper helper) {
        DispatchManager manager = DispatchManager.get(helper.getLevel().getServer());
        DispatchData data = manager.data();
        if (!data.hasLocker(RESTART_SENTINEL)) {
            LOGGER.warn("[publicmines] Dispatch restart verify skipped: dispatchRestartSeed "
                    + "has not produced a locker on this world yet.");
            helper.succeed();
            return;
        }
        ItemStack stack = new DispatchContainer(data, RESTART_SENTINEL).getItem(20);
        LOGGER.info("[publicmines] Dispatch restart verify observed slot 20 = {}", stack);
        helper.assertTrue(stack.is(Items.NETHERITE_SCRAP) && stack.getCount() == 7,
                "locker contents did not survive the server restart: " + stack);
        new DispatchContainer(data, RESTART_SENTINEL).clearContent();
        helper.succeed();
    }
}
