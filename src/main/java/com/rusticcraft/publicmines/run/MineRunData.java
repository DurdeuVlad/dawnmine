package com.rusticcraft.publicmines.run;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Authoritative persisted state of the single shared public-mine run.
 *
 * <p>Stored on the overworld {@link net.minecraft.world.level.storage.DimensionDataStorage}
 * as {@code data/publicmines_run.dat}; one record covers every player and
 * every dimension involved in the run. The record is versioned with
 * {@code schema} so later milestones can migrate fields.
 */
public class MineRunData extends SavedData {
    public static final String DATA_NAME = "publicmines_run";
    private static final int SCHEMA = 1;

    private int schema = SCHEMA;
    private RunStatus status = RunStatus.UNINITIALIZED;
    private String runId = "";
    private long runSequence = 0;
    private long seed = 0;
    private String dayId = "";
    private String lastError = "";
    // Active mine region in chunk coordinates (inclusive).
    private int boundMinChunkX = 0;
    private int boundMinChunkZ = 0;
    private int boundMaxChunkX = -1;
    private int boundMaxChunkZ = -1;
    private BlockPos mineArrival = null;
    private BlockPos surfaceExit = null;
    /** Number of generated frontier regions claimed so far; M2 advances this. */
    private int frontierRegions = 0;
    private final List<OwnedVolume> ownedVolumes = new ArrayList<>();
    /** Per-player surface return position captured on entry. */
    private final Map<UUID, ReturnPoint> returnPoints = new HashMap<>();

    public record ReturnPoint(ResourceKey<Level> dimension, double x, double y, double z, float yaw, float pitch) {}

    public static SavedData.Factory<MineRunData> factory() {
        // Saved data files always carry the current data version, so the
        // chosen fixer type is inert here; a non-null type is required by
        // DimensionDataStorage.
        return new SavedData.Factory<>(MineRunData::new, MineRunData::load,
                DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schema", schema);
        tag.putString("status", status.name());
        tag.putString("runId", runId);
        tag.putLong("runSequence", runSequence);
        tag.putLong("seed", seed);
        tag.putString("dayId", dayId);
        tag.putString("lastError", lastError);
        tag.putIntArray("bounds", new int[] {boundMinChunkX, boundMinChunkZ, boundMaxChunkX, boundMaxChunkZ});
        if (mineArrival != null) {
            tag.putIntArray("arrival", new int[] {mineArrival.getX(), mineArrival.getY(), mineArrival.getZ()});
        }
        if (surfaceExit != null) {
            tag.putIntArray("surfaceExit", new int[] {surfaceExit.getX(), surfaceExit.getY(), surfaceExit.getZ()});
        }
        tag.putInt("frontierRegions", frontierRegions);
        ListTag volumes = new ListTag();
        for (OwnedVolume volume : ownedVolumes) {
            volumes.add(volume.save());
        }
        tag.put("ownedVolumes", volumes);
        CompoundTag returns = new CompoundTag();
        for (Map.Entry<UUID, ReturnPoint> entry : returnPoints.entrySet()) {
            ReturnPoint r = entry.getValue();
            CompoundTag rt = new CompoundTag();
            rt.putString("dim", r.dimension().location().toString());
            rt.putDouble("x", r.x());
            rt.putDouble("y", r.y());
            rt.putDouble("z", r.z());
            rt.putFloat("yaw", r.yaw());
            rt.putFloat("pitch", r.pitch());
            returns.put(entry.getKey().toString(), rt);
        }
        tag.put("returnPoints", returns);
        return tag;
    }

    public static MineRunData load(CompoundTag tag, HolderLookup.Provider registries) {
        MineRunData data = new MineRunData();
        data.schema = tag.getInt("schema");
        data.status = parseStatus(tag.getString("status"));
        data.runId = tag.getString("runId");
        data.runSequence = tag.getLong("runSequence");
        data.seed = tag.getLong("seed");
        data.dayId = tag.getString("dayId");
        data.lastError = tag.getString("lastError");
        int[] bounds = tag.getIntArray("bounds");
        if (bounds.length == 4) {
            data.boundMinChunkX = bounds[0];
            data.boundMinChunkZ = bounds[1];
            data.boundMaxChunkX = bounds[2];
            data.boundMaxChunkZ = bounds[3];
        }
        int[] arrival = tag.getIntArray("arrival");
        if (arrival.length == 3) {
            data.mineArrival = new BlockPos(arrival[0], arrival[1], arrival[2]);
        }
        int[] exit = tag.getIntArray("surfaceExit");
        if (exit.length == 3) {
            data.surfaceExit = new BlockPos(exit[0], exit[1], exit[2]);
        }
        data.frontierRegions = tag.getInt("frontierRegions");
        for (Tag t : tag.getList("ownedVolumes", Tag.TAG_COMPOUND)) {
            try {
                OwnedVolume volume = OwnedVolume.load((CompoundTag) t);
                if (volume != null) {
                    data.ownedVolumes.add(volume);
                }
            } catch (RuntimeException ignored) {
                // drop malformed entries rather than failing the whole load
            }
        }
        CompoundTag returns = tag.getCompound("returnPoints");
        for (String key : returns.getAllKeys()) {
            CompoundTag rt = returns.getCompound(key);
            try {
                UUID uuid = UUID.fromString(key);
                ResourceKey<Level> dim = ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION,
                        ResourceLocation.parse(rt.getString("dim")));
                data.returnPoints.put(uuid, new ReturnPoint(
                        dim, rt.getDouble("x"), rt.getDouble("y"), rt.getDouble("z"),
                        rt.getFloat("yaw"), rt.getFloat("pitch")));
            } catch (RuntimeException ignored) {
                // drop malformed entries (bad UUIDs, bad dimension ids) rather
                // than failing the whole load
            }
        }
        return data;
    }

    private static RunStatus parseStatus(String name) {
        try {
            return RunStatus.valueOf(name);
        } catch (IllegalArgumentException e) {
            return RunStatus.FAILED;
        }
    }

    public int schema() { return schema; }
    public RunStatus status() { return status; }
    public String runId() { return runId; }
    public long runSequence() { return runSequence; }
    public long seed() { return seed; }
    public String dayId() { return dayId; }
    public String lastError() { return lastError; }
    public int boundMinChunkX() { return boundMinChunkX; }
    public int boundMinChunkZ() { return boundMinChunkZ; }
    public int boundMaxChunkX() { return boundMaxChunkX; }
    public int boundMaxChunkZ() { return boundMaxChunkZ; }
    public BlockPos mineArrival() { return mineArrival; }
    public BlockPos surfaceExit() { return surfaceExit; }
    public int frontierRegions() { return frontierRegions; }
    public List<OwnedVolume> ownedVolumes() { return List.copyOf(ownedVolumes); }

    void beginNewRun(String runId, long sequence, long seed, String dayId) {
        this.runId = runId;
        this.runSequence = sequence;
        this.seed = seed;
        this.dayId = dayId;
        this.lastError = "";
        this.frontierRegions = 0;
        this.ownedVolumes.clear();
        this.returnPoints.clear();
        this.status = RunStatus.INITIALIZING;
        setDirty();
    }

    void setBounds(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        this.boundMinChunkX = minChunkX;
        this.boundMinChunkZ = minChunkZ;
        this.boundMaxChunkX = maxChunkX;
        this.boundMaxChunkZ = maxChunkZ;
        setDirty();
    }

    void setMineArrival(BlockPos pos) { this.mineArrival = pos; setDirty(); }
    void setSurfaceExit(BlockPos pos) { this.surfaceExit = pos; setDirty(); }
    void addOwnedVolume(OwnedVolume volume) { this.ownedVolumes.add(volume); setDirty(); }

    /** Records a lifecycle transition. Callers should normally go through
     * {@link MineManager} so invariants (entry lock, owned volumes) are kept. */
    public void markStatus(RunStatus status) { this.status = status; setDirty(); }

    void markFailed(String error) {
        this.status = RunStatus.FAILED;
        this.lastError = error == null ? "unknown" : error;
        setDirty();
    }

    void setReturnPoint(UUID player, ReturnPoint point) {
        this.returnPoints.put(player, point);
        setDirty();
    }

    public ReturnPoint returnPoint(UUID player) {
        return this.returnPoints.get(player);
    }

    void clearReturnPoint(UUID player) {
        if (this.returnPoints.remove(player) != null) {
            setDirty();
        }
    }
}
