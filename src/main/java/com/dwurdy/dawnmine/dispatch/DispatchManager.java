package com.dwurdy.dawnmine.dispatch;

import com.dwurdy.dawnmine.run.MineManager;
import com.dwurdy.dawnmine.run.MineRunData;
import com.dwurdy.dawnmine.run.RunStatus;
import com.dwurdy.dawnmine.topology.HubLayout;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Access policy and persistent owner for the in-theme Foreman's Dispatch. */
public final class DispatchManager {
    private static DispatchManager instance;
    private final MinecraftServer server;
    private final DispatchData data;

    private DispatchManager(MinecraftServer server) {
        this.server = server;
        this.data = server.overworld().getDataStorage()
                .computeIfAbsent(DispatchData.factory(), DispatchData.DATA_NAME);
    }

    public static synchronized DispatchManager get(MinecraftServer server) {
        if (instance == null || instance.server != server) instance = new DispatchManager(server);
        return instance;
    }

    public boolean open(ServerPlayer player) {
        if (!data.isAvailable()) {
            player.sendSystemMessage(Component.literal("Foreman's Dispatch is unavailable: " + data.unavailableReason()));
            return false;
        }
        if (!isAuthorizedAtStation(player)) {
            player.sendSystemMessage(Component.literal("Use Foreman's Dispatch at a generated checkpoint or the surface depot."));
            return false;
        }
        UUID owner = player.getUUID();
        if (!data.acquire(owner)) {
            player.sendSystemMessage(Component.literal("Your Dispatch is already open."));
            return false;
        }
        try {
            var result = player.openMenu(new SimpleMenuProvider(
                    (id, inventory, opener) -> new DispatchMenu(id, inventory, player, data,
                            this::isAtStation), Component.literal("Foreman's Dispatch")));
            if (result.isEmpty()) {
                data.release(owner);
                return false;
            }
            return true;
        } catch (RuntimeException exception) {
            data.release(owner);
            if (exception instanceof IllegalStateException) {
                player.sendSystemMessage(Component.literal("Your Dispatch could not be opened safely. Please contact a server administrator."));
                return false;
            }
            throw exception;
        }
    }

    /** Carried stacks lifted out of open menus while a dimension travel is
     * in flight; keyed by player UUID, delivered on dimension change or
     * restored to the menu if the travel was cancelled. */
    private final Map<UUID, ItemStack> travelEscrow = new HashMap<>();

    /** READY-gated admission for NEW opens: opening a locker requires a
     * prepared, open run. An already-open session's validity is checked
     * against {@link #isAtStation} instead. */
    public boolean isAuthorizedAtStation(ServerPlayer player) {
        MineRunData run = MineManager.get(server).data();
        return run.status() == RunStatus.READY && isAtStation(player);
    }

    /** Physical station check only: the player must stand inside a current
     * owned station zone (surface exit, hub safe zone, or a checkpoint
     * station volume). Lifetime of an open Dispatch session is a physical
     * station presence matter, not run admission: a cancelled leave or an
     * evacuation stall keeps the station zone intact, so the menu stays
     * valid until the player actually leaves the station or dimension. */
    private boolean isAtStation(ServerPlayer player) {
        MineRunData run = MineManager.get(server).data();
        if (player.serverLevel() == null) return false;
        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();
        if (level.dimension().equals(Level.OVERWORLD)) {
            MineRunData.SafeZone exit = run.safeZone(MineRunData.surfaceExitSafeZoneId());
            return exit != null && exit.contains(level, pos);
        }
        if (!level.dimension().equals(MineManager.MINE_DIMENSION)) return false;
        MineRunData.SafeZone hub = run.safeZone(ResourceLocation.parse(HubLayout.SAFE_ZONE_ID));
        if (hub != null && hub.contains(level, pos)) return true;
        return run.checkpointStations().stream().anyMatch(station ->
                station.safeVolume().dimension().equals(level.dimension())
                        && station.safeVolume().contains(level, pos));
    }

    /**
     * Server tick closes stale sessions after movement, teleport, or station
     * removal, then releases any lock whose menu no longer exists (vanilla
     * skips {@code removed} on some close paths, so the lock must not rely on
     * it alone). A carried stack pulled into escrow before a cancelled travel
     * is restored onto the still-open menu here.
     */
    public void tick() {
        Set<UUID> open = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof DispatchMenu menu) {
                if (menu.stillValid(player)) {
                    open.add(player.getUUID());
                } else {
                    player.closeContainer();
                }
            }
            // A cancelled or force-closed travel leaves its escrowed stack
            // here; restore it onto the still-valid menu, else return it to
            // the player directly. Successful travel delivers earlier in
            // PlayerChangedDimensionEvent, so this only ever sees leftovers.
            ItemStack escrow = travelEscrow.remove(player.getUUID());
            if (escrow != null) {
                if (player.containerMenu instanceof DispatchMenu menu
                        && menu.getCarried().isEmpty() && menu.stillValid(player)) {
                    menu.setCarried(escrow);
                } else {
                    player.getInventory().placeItemBackInInventory(escrow);
                }
            }
        }
        data.retainSessions(open);
    }

    /**
     * Pulls an open menu's carried stack into escrow BEFORE any other travel
     * listener can force-close the menu. Menu close during {@code
     * changeDimension} calls {@code removed()} while {@code level()} still
     * reports the mine, which would drop the stack into resetting terrain;
     * an emptied cursor drops nothing.
     */
    public void secureCarriedForTravel(ServerPlayer player) {
        if (player.containerMenu != player.inventoryMenu
                && !player.containerMenu.getCarried().isEmpty()) {
            travelEscrow.put(player.getUUID(), player.containerMenu.getCarried());
            player.containerMenu.setCarried(ItemStack.EMPTY);
        }
    }

    /** Returns an escrowed carried stack at the player's post-travel level:
     * into the inventory, or as a drop at the actual destination when the
     * inventory is full. */
    public void deliverTravelEscrow(ServerPlayer player) {
        ItemStack stack = travelEscrow.remove(player.getUUID());
        if (stack != null && !stack.isEmpty()) {
            player.getInventory().placeItemBackInInventory(stack);
        }
    }

    /**
     * Vanilla never invokes {@code containerMenu.removed} when a player
     * disconnects, so a logout with the Dispatch open would leak the session
     * lock and silently delete the cursor stack. Closing the menu here returns
     * the carried stack to the player inventory (persisted with the player
     * record written right after this event) and releases the session lock.
     */
    public void onPlayerLogout(ServerPlayer player) {
        deliverTravelEscrow(player);
        if (player.containerMenu instanceof DispatchMenu menu) {
            ItemStack carried = menu.getCarried();
            if (!carried.isEmpty()) {
                menu.setCarried(ItemStack.EMPTY);
                player.getInventory().placeItemBackInInventory(carried);
            }
            player.doCloseContainer();
        }
        data.release(player.getUUID());
    }

    /** Visible for tests in this package. */
    DispatchData data() {
        return data;
    }
}
