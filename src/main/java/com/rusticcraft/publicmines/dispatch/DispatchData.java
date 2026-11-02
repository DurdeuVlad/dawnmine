package com.rusticcraft.publicmines.dispatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

/** Persistent, player-owned Dispatch lockers. This data is deliberately independent of the daily run. */
public final class DispatchData extends SavedData {
    public static final String DATA_NAME = "publicmines_dispatch";
    public static final int SLOT_COUNT = 54;
    private static final int SCHEMA = 1;
    private static final int MAX_LOCKERS = 10_000;

    private final Map<UUID, List<ItemStack>> lockers = new HashMap<>();
    private final Set<UUID> activeSessions = new HashSet<>();
    private CompoundTag unsupportedData;

    public static SavedData.Factory<DispatchData> factory() {
        return new SavedData.Factory<>(DispatchData::new, DispatchData::load,
                DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (unsupportedData != null) return unsupportedData.copy();
        tag.putInt("schema", SCHEMA);
        ListTag players = new ListTag();
        for (Map.Entry<UUID, List<ItemStack>> entry : lockers.entrySet()) {
            if (entry.getValue().stream().allMatch(ItemStack::isEmpty)) continue;
            CompoundTag player = new CompoundTag();
            player.putUUID("owner", entry.getKey());
            ListTag slots = new ListTag();
            for (int i = 0; i < SLOT_COUNT; i++) {
                ItemStack stack = entry.getValue().get(i);
                if (!stack.isEmpty()) {
                    CompoundTag item = new CompoundTag();
                    item.putInt("slot", i);
                    item.put("item", stack.save(registries));
                    slots.add(item);
                }
            }
            player.put("items", slots);
            players.add(player);
        }
        tag.put("players", players);
        return tag;
    }

    public static DispatchData load(CompoundTag tag, HolderLookup.Provider registries) {
        DispatchData data = new DispatchData();
        int schema = tag.getInt("schema");
        if (schema > SCHEMA || schema < 0 || (tag.contains("schema") && schema != SCHEMA)) {
            data.unsupportedData = tag.copy();
            return data;
        }
        if (!tag.contains("players", Tag.TAG_LIST)) {
            if (tag.isEmpty()) return data;
            data.unsupportedData = tag.copy();
            return data;
        }
        ListTag rawPlayers = (ListTag) tag.get("players");
        if (rawPlayers.getElementType() != Tag.TAG_COMPOUND && !rawPlayers.isEmpty()) {
            data.unsupportedData = tag.copy();
            return data;
        }
        ListTag players = rawPlayers;
        if (players.size() > MAX_LOCKERS) {
            data.unsupportedData = tag.copy();
            return data;
        }
        try {
            for (Tag raw : players) {
                CompoundTag player = (CompoundTag) raw;
                if (!player.hasUUID("owner")) throw new IllegalArgumentException("missing owner");
                UUID owner = player.getUUID("owner");
                if (!player.contains("items", Tag.TAG_LIST)) throw new IllegalArgumentException("invalid items list");
                List<ItemStack> contents = emptyContents();
                boolean[] occupied = new boolean[SLOT_COUNT];
                ListTag items = (ListTag) player.get("items");
                if (items.getElementType() != Tag.TAG_COMPOUND && !items.isEmpty()) {
                    throw new IllegalArgumentException("invalid slot list element type");
                }
                if (items.size() > SLOT_COUNT) throw new IllegalArgumentException("too many slots");
                for (Tag rawItem : items) {
                    CompoundTag item = (CompoundTag) rawItem;
                    int slot = item.getInt("slot");
                    if (!item.contains("slot") || slot < 0 || slot >= SLOT_COUNT || occupied[slot]
                            || !item.contains("item", Tag.TAG_COMPOUND)) {
                        throw new IllegalArgumentException("invalid slot entry");
                    }
                    ItemStack.parse(registries, item.getCompound("item")).ifPresentOrElse(stack -> {
                        contents.set(slot, stack);
                        occupied[slot] = true;
                    }, () -> { throw new IllegalArgumentException("unreadable item"); });
                }
                if (data.lockers.putIfAbsent(owner, contents) != null) throw new IllegalArgumentException("duplicate owner");
            }
        } catch (RuntimeException malformed) {
            // Fail closed and preserve the entire original record; never silently delete a player's bank.
            data.lockers.clear();
            data.unsupportedData = tag.copy();
        }
        return data;
    }

    public boolean isAvailable() { return unsupportedData == null; }
    public String unavailableReason() {
        return unsupportedData == null ? "" : "saved Dispatch data is malformed or from a newer version; data preserved";
    }

    /** True when this owner already has a locker record, without creating one. */
    boolean hasLocker(UUID owner) {
        if (!isAvailable()) throw new IllegalStateException(unavailableReason());
        return owner != null && lockers.containsKey(owner);
    }

    List<ItemStack> contents(UUID owner) {
        if (!isAvailable()) throw new IllegalStateException(unavailableReason());
        if (owner == null) throw new IllegalArgumentException("owner required");
        if (lockers.size() >= MAX_LOCKERS && !lockers.containsKey(owner)) throw new IllegalStateException("locker capacity reached");
        return lockers.computeIfAbsent(owner, ignored -> emptyContents());
    }

    boolean acquire(UUID owner) { return isAvailable() && activeSessions.add(owner); }
    void release(UUID owner) { activeSessions.remove(owner); }

    /**
     * Drops session locks that no open Dispatch menu still holds. Vanilla never
     * calls {@code AbstractContainerMenu.removed} for some close paths (notably
     * an abrupt disconnect), so the server tick reconciles against the real
     * open menus instead of trusting release alone.
     */
    void retainSessions(Set<UUID> open) { activeSessions.retainAll(open); }

    void changed() { setDirty(); }

    private static List<ItemStack> emptyContents() {
        List<ItemStack> contents = new ArrayList<>(SLOT_COUNT);
        for (int i = 0; i < SLOT_COUNT; i++) contents.add(ItemStack.EMPTY);
        return contents;
    }
}
