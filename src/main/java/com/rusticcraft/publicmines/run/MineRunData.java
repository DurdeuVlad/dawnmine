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
    private static final int SCHEMA = 2;

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
    /** Deterministic-topology receipt recorded by M2a planning: the SHA-256
     * fingerprint of the canonical plan plus its size, so a replan after a
     * restart can be compared byte-for-byte. */
    private String topologyProfile = "";
    private String topologyFingerprint = "";
    private int topologyNodes = 0;
    private int topologyEdges = 0;
    /** Opaque future-version save data must survive this older mod untouched. */
    private CompoundTag unsupportedSchemaTag;
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
        if (unsupportedSchemaTag != null) {
            return unsupportedSchemaTag.copy();
        }
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
        tag.putString("topologyProfile", topologyProfile);
        tag.putString("topologyFingerprint", topologyFingerprint);
        tag.putInt("topologyNodes", topologyNodes);
        tag.putInt("topologyEdges", topologyEdges);
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
        int savedSchema = tag.getInt("schema");
        if (savedSchema > SCHEMA) {
            data.schema = savedSchema;
            data.status = RunStatus.FAILED;
            data.lastError = "saved run schema " + savedSchema + " is newer than supported schema "
                    + SCHEMA + "; update Public Mines before managing this run (saved data preserved)";
            data.unsupportedSchemaTag = tag.copy();
            return data;
        }
        data.schema = SCHEMA;
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
        data.topologyProfile = tag.getString("topologyProfile");
        data.topologyFingerprint = tag.getString("topologyFingerprint");
        data.topologyNodes = tag.getInt("topologyNodes");
        data.topologyEdges = tag.getInt("topologyEdges");
        if (!data.topologyFingerprint.isEmpty() && !isValidTopologyFingerprint(data.topologyFingerprint)) {
            data.markFailed("saved topology fingerprint is malformed; run '/publicmine recover repair'");
        }
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
        if (savedSchema < SCHEMA) {
            // M2a requires a deterministic topology receipt before a READY
            // run can be trusted. Do not keep an M1 READY record entry-enabled
            // when it has no receipt; staff can safely reseed/rebuild it.
            if (data.status == RunStatus.READY && data.topologyFingerprint.isBlank()) {
                data.status = RunStatus.FAILED;
                data.lastError = "saved run schema " + savedSchema
                        + " has no topology receipt; run '/publicmine recover repair'";
            }
            data.setDirty();
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
    public boolean hasUnsupportedSchema() { return unsupportedSchemaTag != null; }
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
    public String topologyProfile() { return topologyProfile; }
    public String topologyFingerprint() { return topologyFingerprint; }
    public boolean hasValidTopologyFingerprint() {
        return isValidTopologyFingerprint(topologyFingerprint);
    }
    public String topologyFingerprintPrefix() {
        if (topologyFingerprint.isEmpty()) {
            return "(not planned)";
        }
        return hasValidTopologyFingerprint() ? topologyFingerprint.substring(0, 12) : "(invalid)";
    }
    public int topologyNodes() { return topologyNodes; }
    public int topologyEdges() { return topologyEdges; }
    public List<OwnedVolume> ownedVolumes() { return List.copyOf(ownedVolumes); }

    public static boolean isValidTopologyFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.length() != 64) {
            return false;
        }
        for (int i = 0; i < fingerprint.length(); i++) {
            char c = fingerprint.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    void beginNewRun(String runId, long sequence, long seed, String dayId) {
        this.runId = runId;
        this.runSequence = sequence;
        this.seed = seed;
        this.dayId = dayId;
        this.lastError = "";
        this.frontierRegions = 0;
        this.topologyProfile = "";
        this.topologyFingerprint = "";
        this.topologyNodes = 0;
        this.topologyEdges = 0;
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

    /** Records the validated topology receipt for the active run. */
    void setTopology(String profile, String fingerprint, int nodes, int edges) {
        this.topologyProfile = profile;
        this.topologyFingerprint = fingerprint;
        this.topologyNodes = nodes;
        this.topologyEdges = edges;
        setDirty();
    }

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
