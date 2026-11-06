package com.dwurdy.dawnmine.run;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The staff-bound surface teleporter console: a single vanilla lodestone in
 * the OVERWORLD plus a pre-computed safe landing beside it. Persists across
 * daily run resets; never placed or modified by the mod - the operator owns
 * the physical block, this data only records the binding.
 */
public final class TeleporterData extends SavedData {
    public static final String DATA_NAME = "dawnmine_teleporter";

    private BlockPos lodestone;
    private BlockPos safeLanding;

    public record Binding(BlockPos lodestone, BlockPos safeLanding) { }

    public static TeleporterData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(TeleporterData::new, TeleporterData::load),
                DATA_NAME);
    }

    public static TeleporterData load(CompoundTag tag, HolderLookup.Provider provider) {
        TeleporterData data = new TeleporterData();
        int[] stone = tag.getIntArray("lodestone");
        int[] landing = tag.getIntArray("safeLanding");
        if (stone.length == 3 && landing.length == 3) {
            data.lodestone = new BlockPos(stone[0], stone[1], stone[2]);
            data.safeLanding = new BlockPos(landing[0], landing[1], landing[2]);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        if (lodestone != null) {
            tag.putIntArray("lodestone",
                    new int[] {lodestone.getX(), lodestone.getY(), lodestone.getZ()});
        }
        if (safeLanding != null) {
            tag.putIntArray("safeLanding",
                    new int[] {safeLanding.getX(), safeLanding.getY(), safeLanding.getZ()});
        }
        return tag;
    }

    /** The current binding, or null when unbound/incomplete. */
    public Binding binding() {
        return lodestone != null && safeLanding != null
                ? new Binding(lodestone, safeLanding) : null;
    }

    /** Records a binding; never overwrites an existing different binding -
     * callers must unbind first. */
    public boolean bind(BlockPos lodestonePos, BlockPos landing) {
        if (lodestonePos == null || landing == null) {
            return false;
        }
        if (this.lodestone != null && !this.lodestone.equals(lodestonePos)) {
            return false;
        }
        this.lodestone = lodestonePos.immutable();
        this.safeLanding = landing.immutable();
        setDirty();
        return true;
    }

    /** Clears the binding only; world blocks are never touched. */
    public void unbind() {
        lodestone = null;
        safeLanding = null;
        setDirty();
    }

    public static TeleporterData get(MinecraftServer server) {
        return get(server.overworld());
    }
}
