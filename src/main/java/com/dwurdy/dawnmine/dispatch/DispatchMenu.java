package com.dwurdy.dawnmine.dispatch;

import com.dwurdy.dawnmine.run.MineManager;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/** Vanilla six-row chest UI with server-side owner and station checks. */
final class DispatchMenu extends ChestMenu {
    private final UUID owner;
    private final DispatchData data;
    private final Predicate<ServerPlayer> authorizedAtStation;

    DispatchMenu(int id, Inventory inventory, ServerPlayer player, DispatchData data,
                 Predicate<ServerPlayer> authorizedAtStation) {
        super(MenuType.GENERIC_9x6, id, inventory, new DispatchContainer(data, player.getUUID()), 6);
        this.owner = player.getUUID();
        this.data = data;
        this.authorizedAtStation = authorizedAtStation;
    }

    @Override public boolean stillValid(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || !owner.equals(player.getUUID())) {
            return false;
        }
        if (authorizedAtStation.test(serverPlayer)) return true;
        // A player still inside the mine but off-station can only be
        // mid-evacuation (entry never opens a locker outside a station).
        // Closing the menu here would drop the carried stack into resetting
        // terrain, so the session stays parked until the player has actually
        // left the dimension; logout and death have their own recovery paths.
        return serverPlayer.serverLevel().dimension().equals(MineManager.MINE_DIMENSION);
    }

    /** Shift-click moves fail atomically; tell the player why nothing moved. */
    @Override public ItemStack quickMoveStack(Player player, int index) {
        ItemStack moved = super.quickMoveStack(player, index);
        if (moved.isEmpty() && index >= 0 && index < slots.size()
                && !slots.get(index).getItem().isEmpty()
                && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(Component.literal(index < DispatchData.SLOT_COUNT
                    ? "Your inventory is full." : "Your Foreman's Dispatch is full."));
        }
        return moved;
    }

    @Override public void removed(Player player) {
        super.removed(player);
        data.release(owner);
    }
}
