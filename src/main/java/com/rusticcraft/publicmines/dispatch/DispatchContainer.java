package com.rusticcraft.publicmines.dispatch;

import java.util.List;
import java.util.UUID;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** A live view over the UUID-owned persistent slots; menu operations mutate this authoritative container. */
final class DispatchContainer implements Container {
    private final DispatchData data;
    private final UUID owner;
    private final List<ItemStack> contents;

    DispatchContainer(DispatchData data, UUID owner) {
        this.data = data;
        this.owner = owner;
        this.contents = data.contents(owner);
    }

    @Override public int getContainerSize() { return DispatchData.SLOT_COUNT; }
    @Override public boolean isEmpty() { return contents.stream().allMatch(ItemStack::isEmpty); }
    @Override public ItemStack getItem(int slot) { return slot >= 0 && slot < getContainerSize() ? contents.get(slot) : ItemStack.EMPTY; }
    @Override public ItemStack removeItem(int slot, int amount) {
        if (slot < 0 || slot >= getContainerSize()) return ItemStack.EMPTY;
        ItemStack result = ContainerHelper.removeItem(contents, slot, amount);
        if (!result.isEmpty()) setChanged();
        return result;
    }
    @Override public ItemStack removeItemNoUpdate(int slot) {
        if (slot < 0 || slot >= getContainerSize()) return ItemStack.EMPTY;
        ItemStack result = contents.set(slot, ItemStack.EMPTY);
        if (!result.isEmpty()) setChanged();
        return result;
    }
    @Override public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= getContainerSize()) return;
        contents.set(slot, stack);
        if (stack.getCount() > getMaxStackSize()) stack.setCount(getMaxStackSize());
        setChanged();
    }
    @Override public int getMaxStackSize() { return 64; }
    @Override public void setChanged() { data.changed(); }
    @Override public boolean stillValid(Player player) { return player != null && player.getUUID().equals(owner); }
    @Override public void clearContent() {
        for (int i = 0; i < contents.size(); i++) contents.set(i, ItemStack.EMPTY);
        setChanged();
    }
    @Override public void startOpen(Player player) {}
    @Override public void stopOpen(Player player) {}
}
