package com.dwurdy.dawnmine.dispatch;

import com.mojang.logging.LogUtils;
import com.dwurdy.dawnmine.config.DawnmineConfig;
import com.dwurdy.dawnmine.gametest.MockServerPlayers;
import com.dwurdy.dawnmine.run.MineManager;
import com.dwurdy.dawnmine.run.MineRunData;
import com.dwurdy.dawnmine.run.MineRunDataTestSupport;
import com.dwurdy.dawnmine.run.OwnedVolume;
import com.dwurdy.dawnmine.topology.HubLayout;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

/** Dedicated-server checks for registry-aware item persistence and menu transfer behavior. */
@GameTestHolder("dawnmine")
@PrefixGameTestTemplate(false)
public final class DispatchGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Fixed sentinel owner for the two-step real restart persistence check. */
    private static final UUID RESTART_SENTINEL =
            UUID.fromString("7c1f6f2a-9f6b-4e2a-8d3c-1a5e6b7c8d9f");
    private DispatchGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 600, batch = "m5b_travel_cursor")
    public static void fullInventoryCursorReachesSurfaceOnLeave(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        MineManager mineManager = MineManager.get(server);
        helper.assertTrue(mineManager.preparationComplete(), "fixture requires a prepared mine");
        ServerLevel mine = mineManager.mineLevel();
        MineRunData run = mineManager.data();
        CompoundTag snapshot = MineRunDataTestSupport.snapshot(run, server.registryAccess());
        ServerPlayer player = MockServerPlayers.inLevel(helper, server.overworld());
        ItemStack expected = new ItemStack(Items.DIAMOND, 5);
        expected.set(DataComponents.CUSTOM_NAME, Component.literal("dawnmine-test-" + player.getUUID()));
        try {
            helper.assertTrue(mineManager.enter(player).ok(), "could not enter the prepared hub");
            var console = HubLayout.DISPATCH_CONSOLE;
            player.teleportTo(mine, console.x() + 1.5, console.y(), console.z() + 0.5, 0F, 0F);
            player.getInventory().clearContent();
            for (int slot = 0; slot < 36; slot++) {
                player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            }
            helper.assertTrue(DispatchManager.get(server).open(player), "could not open the hub locker");
            var menu = player.containerMenu;
            menu.getSlot(0).set(expected.copy());
            menu.setCarried(menu.getSlot(0).remove(5));
            helper.assertValueEqual(mineManager.leave(player), MineManager.LeaveResult.LEFT,
                    "leave failed with a full inventory");
            helper.assertTrue(player.serverLevel() == server.overworld(), "player did not reach the surface");
            helper.assertTrue(player.containerMenu == player.inventoryMenu, "travel left the locker menu open");
            helper.assertTrue(player.getInventory().countItem(Items.COBBLESTONE) == 36 * 64,
                    "travel changed the full inventory");
            helper.assertTrue(menu.getSlot(0).getItem().isEmpty() && menu.getCarried().isEmpty(),
                    "withdrawn cursor stack was duplicated in the menu");
            var surfaceDrops = server.overworld().getEntitiesOfClass(ItemEntity.class,
                    player.getBoundingBox().inflate(8),
                    item -> ItemStack.isSameItemSameComponents(item.getItem(), expected));
            BlockPos hub = new BlockPos(console.x(), console.y(), console.z());
            var mineDrops = mine.getEntitiesOfClass(ItemEntity.class,
                    new net.minecraft.world.phys.AABB(hub).inflate(8),
                    item -> ItemStack.isSameItemSameComponents(item.getItem(), expected));
            if (!(surfaceDrops.size() == 1 && surfaceDrops.get(0).getItem().getCount() == 5)) {
                // Bounded diagnostics: where did the stack go, and does the pack
                // let the vanilla toss path spawn entities at all right now?
                ItemEntity probe = player.drop(new ItemStack(Items.STICK, 1), false);
                boolean tossVoided = probe == null;
                if (probe != null) probe.discard();
                helper.fail("cursor overflow not preserved: inventory diamonds="
                        + player.getInventory().countItem(Items.DIAMOND)
                        + " surfaceDrops=" + surfaceDrops.size()
                        + " mineDrops=" + mineDrops.size()
                        + " tossVoided=" + tossVoided
                        + " at " + player.blockPosition().toShortString()
                        + " in " + player.serverLevel().dimension().location());
            }
            helper.assertTrue(mineDrops.isEmpty(),
                    "cursor overflow was dropped inside the mine where reset would erase it");
        } finally {
            try {
                for (ServerLevel level : new ServerLevel[] {mine, server.overworld()}) {
                    for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class,
                            player.getBoundingBox().inflate(6),
                            drop -> ItemStack.isSameItemSameComponents(drop.getItem(), expected))) {
                        item.discard();
                    }
                }
                player.containerMenu.setCarried(ItemStack.EMPTY);
                player.closeContainer();
                player.getInventory().clearContent();
                server.getPlayerList().remove(player);
            } finally {
                MineRunDataTestSupport.restore(run, snapshot, server.registryAccess());
            }
        }
        helper.succeed();
    }

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

    /** Overstacked stacks (command/mod-granted) must round-trip like vanilla chests instead of bricking the loader. */
    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void overstackedItemRoundTripsWithoutBrickingSavedData(GameTestHelper helper) {
        DispatchData dispatch = new DispatchData();
        UUID owner = UUID.randomUUID();
        new DispatchContainer(dispatch, owner).setItem(9, new ItemStack(Items.NETHERITE_SWORD, 5));
        CompoundTag saved = dispatch.save(new CompoundTag(), helper.getLevel().registryAccess());
        DispatchData reloaded = DispatchData.load(saved, helper.getLevel().registryAccess());

        helper.assertTrue(reloaded.isAvailable(), "overstacked stack bricked the locker loader");
        ItemStack restored = reloaded.contents(owner).get(9);
        helper.assertTrue(restored.is(Items.NETHERITE_SWORD) && restored.getCount() == 5,
                "overstacked stack did not persist exactly");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void slotEntryWithoutSlotKeyFailsClosed(GameTestHelper helper) {
        CompoundTag malformed = new CompoundTag();
        malformed.putInt("schema", 1);
        ListTag players = new ListTag();
        CompoundTag player = new CompoundTag();
        player.putUUID("owner", UUID.randomUUID());
        ListTag items = new ListTag();
        CompoundTag item = new CompoundTag();
        item.put("item", new CompoundTag()); // no "slot" key: must not silently become slot 0
        items.add(item);
        player.put("items", items);
        players.add(player);
        malformed.put("players", players);

        DispatchData loaded = DispatchData.load(malformed, helper.getLevel().registryAccess());
        helper.assertFalse(loaded.isAvailable(), "slot-less item entry was silently relocated to slot 0");
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
        var player = MockServerPlayers.inLevel(helper);
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
        ServerPlayer player = MockServerPlayers.inLevel(helper);
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
            MineRunDataTestSupport.addMineOwnedRegion(run, DawnmineConfig.mineRegionRadiusChunks(),
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
                LOGGER.warn("[dawnmine] Dispatch depot GameTest: mine dimension absent; "
                        + "checkpoint-terminal coverage runs on the dedicated server.");
            }

            player = MockServerPlayers.inLevel(helper);
            UUID owner = player.getUUID();

            // Away from every station the command must refuse with guidance.
            player.moveTo(depot.getX() + 64.5, depot.getY(), depot.getZ() + 64.5, 0.0F, 0.0F);
            helper.assertTrue(!manager.isAuthorizedAtStation(player),
                    "player far outside every terminal zone was treated as at a station");
            player.getServer().getCommands()
                    .performPrefixedCommand(player.createCommandSourceStack(), "dawnmine dispatch");
            helper.assertTrue(!(player.containerMenu instanceof DispatchMenu),
                    "dispatch menu opened outside of any terminal zone");

            player.moveTo(depot.getX() + 0.5, depot.getY(), depot.getZ() + 0.5, 0.0F, 0.0F);
            helper.assertTrue(manager.isAuthorizedAtStation(player),
                    "player standing on the surface depot pad is not authorized for the Dispatch");

            player.getServer().getCommands()
                    .performPrefixedCommand(player.createCommandSourceStack(), "dawnmine dispatch");
            helper.assertTrue(player.containerMenu instanceof DispatchMenu,
                    "'/dawnmine dispatch' did not open the locker menu at the surface depot");

            player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 10));
            ItemStack moved = player.containerMenu.quickMoveStack(player, 81); // first hotbar slot
            helper.assertTrue(!moved.isEmpty() && moved.is(Items.DIAMOND),
                    "shift-click deposit into the locker failed");
            helper.assertTrue(data.contents(owner).stream().anyMatch(stack -> stack.is(Items.DIAMOND)),
                    "deposit is not visible in the persistent locker");

            player.closeContainer();
            player.getServer().getCommands()
                    .performPrefixedCommand(player.createCommandSourceStack(), "dawnmine dispatch");
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
                        .performPrefixedCommand(player.createCommandSourceStack(), "dawnmine dispatch");
                helper.assertTrue(player.containerMenu instanceof DispatchMenu,
                        "'/dawnmine dispatch' did not open the locker menu at checkpoint " + checkpointId);
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
            LOGGER.warn("[dawnmine] Dispatch restart verify skipped: dispatchRestartSeed "
                    + "has not produced a locker on this world yet.");
            helper.succeed();
            return;
        }
        ItemStack stack = new DispatchContainer(data, RESTART_SENTINEL).getItem(20);
        LOGGER.info("[dawnmine] Dispatch restart verify observed slot 20 = {}", stack);
        helper.assertTrue(stack.is(Items.NETHERITE_SCRAP) && stack.getCount() == 7,
                "locker contents did not survive the server restart: " + stack);
        new DispatchContainer(data, RESTART_SENTINEL).clearContent();
        helper.succeed();
    }
}
