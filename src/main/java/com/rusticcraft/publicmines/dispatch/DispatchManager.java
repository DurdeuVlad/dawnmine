package com.rusticcraft.publicmines.dispatch;

import com.rusticcraft.publicmines.run.MineManager;
import com.rusticcraft.publicmines.run.MineRunData;
import com.rusticcraft.publicmines.run.RunStatus;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
                            this::isAuthorizedAtStation), Component.literal("Foreman's Dispatch")));
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

    public boolean isAuthorizedAtStation(ServerPlayer player) {
        MineRunData run = MineManager.get(server).data();
        if (run.status() != RunStatus.READY || player.serverLevel() == null) return false;
        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();
        if (level.dimension().equals(Level.OVERWORLD)) {
            MineRunData.SafeZone exit = run.safeZone(MineRunData.surfaceExitSafeZoneId());
            return exit != null && exit.contains(level, pos);
        }
        if (!level.dimension().equals(MineManager.MINE_DIMENSION)) return false;
        return run.checkpointStations().stream().anyMatch(station ->
                station.safeVolume().dimension().equals(level.dimension())
                        && station.safeVolume().contains(level, pos));
    }

    /**
     * Server tick closes stale sessions after movement, teleport, or a run
     * transition, then releases any lock whose menu no longer exists (vanilla
     * skips {@code removed} on some close paths, so the lock must not rely on
     * it alone).
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
        }
        data.retainSessions(open);
    }

    /**
     * Vanilla never invokes {@code containerMenu.removed} when a player
     * disconnects, so a logout with the Dispatch open would leak the session
     * lock and silently delete the cursor stack. Closing the menu here returns
     * the carried stack to the player inventory (persisted with the player
     * record written right after this event) and releases the session lock.
     */
    public void onPlayerLogout(ServerPlayer player) {
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
