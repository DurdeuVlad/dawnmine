package com.rusticcraft.publicmines.run;

import com.rusticcraft.publicmines.config.PublicMineConfig;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final int SCHEMA = 3;
    /** Segment identity count is below the topology planner's hard node cap,
     * with room for edges and a bounded pending frontier. */
    static final int MAX_GENERATION_SEGMENTS = 16_384;
    static final int MAX_GENERATION_SEGMENT_ID_LENGTH = 96;
    static final int MAX_GENERATION_OPERATION_CURSOR = 1_000_000;

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
    /** Fingerprint of the topology + geometry algorithm that owns progress. */
    private String generationPlanFingerprint = "";
    /** Run-pinned generation settings; config edits apply to the next run. */
    private boolean generationNetherrackEnabled;
    private int generationNetherrackInterval = PublicMineConfig.NETHERRACK_INTERVAL_MIN;
    /** FIFO order is stable across save/load and determines shared expansion. */
    private final LinkedHashSet<String> pendingGenerationSegments = new LinkedHashSet<>();
    private final LinkedHashSet<String> completedGenerationSegments = new LinkedHashSet<>();
    /** Only one segment is applied at once so the cursor has one unambiguous owner. */
    private String activeGenerationSegment = "";
    private int activeGenerationCursor = 0;
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
        tag.putString("generationPlanFingerprint", generationPlanFingerprint);
        tag.putBoolean("generationNetherrackEnabled", generationNetherrackEnabled);
        tag.putInt("generationNetherrackInterval", generationNetherrackInterval);
        tag.put("pendingGenerationSegments", saveStringSet(pendingGenerationSegments));
        tag.put("completedGenerationSegments", saveStringSet(completedGenerationSegments));
        tag.putString("activeGenerationSegment", activeGenerationSegment);
        tag.putInt("activeGenerationCursor", activeGenerationCursor);
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
        data.loadGenerationState(tag);
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

    private static ListTag saveStringSet(Set<String> values) {
        ListTag tags = new ListTag();
        for (String value : values) {
            tags.add(net.minecraft.nbt.StringTag.valueOf(value));
        }
        return tags;
    }

    private void loadGenerationState(CompoundTag tag) {
        if (tag.contains("generationPlanFingerprint")
                && !tag.contains("generationPlanFingerprint", Tag.TAG_STRING)) {
            markGenerationStateInvalid("generation plan fingerprint has the wrong NBT type");
        }
        generationPlanFingerprint = tag.getString("generationPlanFingerprint");
        generationNetherrackEnabled = tag.getBoolean("generationNetherrackEnabled");
        int savedInterval = tag.contains("generationNetherrackInterval", Tag.TAG_INT)
                ? tag.getInt("generationNetherrackInterval") : PublicMineConfig.NETHERRACK_INTERVAL_MIN;
        if (savedInterval < PublicMineConfig.NETHERRACK_INTERVAL_MIN
                || savedInterval > PublicMineConfig.NETHERRACK_INTERVAL_MAX) {
            markGenerationStateInvalid("saved Netherrack interval is outside the supported range");
            savedInterval = PublicMineConfig.NETHERRACK_INTERVAL_MIN;
        }
        generationNetherrackInterval = savedInterval;
        if (!generationPlanFingerprint.isEmpty()
                && !isValidTopologyFingerprint(generationPlanFingerprint)) {
            markGenerationStateInvalid("generation plan fingerprint is malformed");
            generationPlanFingerprint = "";
        }
        readSegmentIds(tag, "pendingGenerationSegments", pendingGenerationSegments);
        readSegmentIds(tag, "completedGenerationSegments", completedGenerationSegments);
        if (tag.contains("activeGenerationSegment")
                && !tag.contains("activeGenerationSegment", Tag.TAG_STRING)) {
            markGenerationStateInvalid("active generation segment has the wrong NBT type");
        }
        activeGenerationSegment = tag.getString("activeGenerationSegment");
        if (!activeGenerationSegment.isEmpty() && !isValidSegmentId(activeGenerationSegment)) {
            markGenerationStateInvalid("active generation segment id is malformed");
            activeGenerationSegment = "";
        }
        if (tag.contains("activeGenerationCursor")
                && !tag.contains("activeGenerationCursor", Tag.TAG_INT)) {
            markGenerationStateInvalid("active generation cursor has the wrong NBT type");
        }
        activeGenerationCursor = tag.getInt("activeGenerationCursor");
        if (activeGenerationCursor < 0 || activeGenerationCursor > MAX_GENERATION_OPERATION_CURSOR) {
            markGenerationStateInvalid("active generation operation cursor is outside the supported range");
            activeGenerationCursor = 0;
        }
        if (!activeGenerationSegment.isEmpty()) {
            if (completedGenerationSegments.contains(activeGenerationSegment)) {
                markGenerationStateInvalid("active generation segment is also marked completed");
                activeGenerationSegment = "";
                activeGenerationCursor = 0;
            } else {
                if (pendingGenerationSegments.size() + completedGenerationSegments.size()
                        >= MAX_GENERATION_SEGMENTS) {
                    markGenerationStateInvalid("active segment exceeds the supported unique segment limit of "
                            + MAX_GENERATION_SEGMENTS);
                    activeGenerationSegment = "";
                    activeGenerationCursor = 0;
                }
                pendingGenerationSegments.remove(activeGenerationSegment);
            }
        } else if (activeGenerationCursor != 0) {
            markGenerationStateInvalid("generation cursor exists without an active segment");
            activeGenerationCursor = 0;
        }
        if ((!pendingGenerationSegments.isEmpty() || !completedGenerationSegments.isEmpty()
                    || !activeGenerationSegment.isEmpty())
                && generationPlanFingerprint.isEmpty()) {
            markGenerationStateInvalid("segment progress has no generation plan fingerprint");
        }
        boolean overlapsCompleted = pendingGenerationSegments.removeIf(completedGenerationSegments::contains);
        if (overlapsCompleted) {
            markGenerationStateInvalid("a segment is listed as both pending and completed");
        }
    }

    private void readSegmentIds(CompoundTag tag, String key, LinkedHashSet<String> destination) {
        if (!tag.contains(key)) {
            return;
        }
        Tag stored = tag.get(key);
        if (!(stored instanceof ListTag list)) {
            markGenerationStateInvalid(key + " is not a list");
            return;
        }
        if (list.size() > MAX_GENERATION_SEGMENTS) {
            markGenerationStateInvalid(key + " exceeds the supported segment limit of "
                    + MAX_GENERATION_SEGMENTS);
        }
        int limit = Math.min(list.size(), MAX_GENERATION_SEGMENTS);
        for (int index = 0; index < limit; index++) {
            Tag value = list.get(index);
            if (!(value instanceof net.minecraft.nbt.StringTag stringTag)) {
                markGenerationStateInvalid(key + " contains a non-string entry");
                continue;
            }
            String id = stringTag.getAsString();
            if (!isValidSegmentId(id)) {
                markGenerationStateInvalid(key + " contains a malformed segment id");
                continue;
            }
            if (!destination.contains(id)
                    && pendingGenerationSegments.size() + completedGenerationSegments.size()
                            >= MAX_GENERATION_SEGMENTS) {
                markGenerationStateInvalid(key + " exceeds the supported unique segment limit of "
                        + MAX_GENERATION_SEGMENTS);
                break;
            }
            destination.add(id);
        }
        if (destination == pendingGenerationSegments) {
            destination.removeAll(completedGenerationSegments);
        }
    }

    private void markGenerationStateInvalid(String detail) {
        markFailed("saved generation progress is invalid: " + detail
                + "; run '/publicmine recover repair'");
    }

    private static boolean isValidSegmentId(String id) {
        if (id == null || id.isBlank() || id.length() > MAX_GENERATION_SEGMENT_ID_LENGTH) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == ':' || c == '_' || c == '-')) {
                return false;
            }
        }
        return true;
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
    public String generationPlanFingerprint() { return generationPlanFingerprint; }
    public boolean generationNetherrackEnabled() { return generationNetherrackEnabled; }
    public int generationNetherrackInterval() { return generationNetherrackInterval; }
    public List<String> pendingGenerationSegments() { return List.copyOf(pendingGenerationSegments); }
    public List<String> completedGenerationSegments() { return List.copyOf(completedGenerationSegments); }
    public String activeGenerationSegment() { return activeGenerationSegment; }
    public int activeGenerationCursor() { return activeGenerationCursor; }

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
        clearGenerationProgress();
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

    /** Pins generation progress to the immutable geometry plan for this run. */
    public boolean beginGeneration(String fingerprint) {
        return beginGeneration(fingerprint, false, PublicMineConfig.NETHERRACK_INTERVAL_MIN);
    }

    /** Pins the immutable generation identity and the next-run-only palette settings. */
    public boolean beginGeneration(String fingerprint, boolean netherrackEnabled,
                                   int netherrackInterval) {
        if (!isValidTopologyFingerprint(fingerprint)) {
            return false;
        }
        if (netherrackInterval < PublicMineConfig.NETHERRACK_INTERVAL_MIN
                || netherrackInterval > PublicMineConfig.NETHERRACK_INTERVAL_MAX) {
            return false;
        }
        if (!generationPlanFingerprint.isEmpty()) {
            return generationPlanFingerprint.equals(fingerprint)
                    && generationNetherrackEnabled == netherrackEnabled
                    && generationNetherrackInterval == netherrackInterval;
        }
        generationPlanFingerprint = fingerprint;
        generationNetherrackEnabled = netherrackEnabled;
        generationNetherrackInterval = netherrackInterval;
        setDirty();
        return true;
    }

    /** Adds a segment once, preserving deterministic FIFO enqueue order. */
    public boolean enqueueGenerationSegment(String segmentId) {
        if (generationPlanFingerprint.isEmpty() || !isValidSegmentId(segmentId)
                || pendingGenerationSegments.contains(segmentId)
                || completedGenerationSegments.contains(segmentId)
                || activeGenerationSegment.equals(segmentId)
                || pendingGenerationSegments.size() + completedGenerationSegments.size()
                        + (activeGenerationSegment.isEmpty() ? 0 : 1)
                        >= MAX_GENERATION_SEGMENTS) {
            return false;
        }
        pendingGenerationSegments.add(segmentId);
        setDirty();
        return true;
    }

    /** Starts the oldest queued segment or returns empty when there is no work. */
    public String beginNextGenerationSegment() {
        if (!activeGenerationSegment.isEmpty()) {
            return activeGenerationSegment;
        }
        if (pendingGenerationSegments.isEmpty()) {
            return "";
        }
        String next = pendingGenerationSegments.iterator().next();
        pendingGenerationSegments.remove(next);
        activeGenerationSegment = next;
        activeGenerationCursor = 0;
        setDirty();
        return activeGenerationSegment;
    }

    /** Advances the active deterministic block-operation cursor. */
    public boolean advanceGenerationCursor(int processedOperations) {
        if (activeGenerationSegment.isEmpty() || processedOperations < 0
                || processedOperations > MAX_GENERATION_OPERATION_CURSOR - activeGenerationCursor) {
            return false;
        }
        if (processedOperations > 0) {
            activeGenerationCursor += processedOperations;
            setDirty();
        }
        return true;
    }

    /** Marks active work complete so it is never queued or rewritten again. */
    public boolean completeActiveGenerationSegment() {
        if (activeGenerationSegment.isEmpty()
                || completedGenerationSegments.size() >= MAX_GENERATION_SEGMENTS) {
            return false;
        }
        completedGenerationSegments.add(activeGenerationSegment);
        activeGenerationSegment = "";
        activeGenerationCursor = 0;
        setDirty();
        return true;
    }

    /** Deterministic block writes make replay from cursor zero repair partial
     * chunks if the SavedData cursor was saved ahead of a chunk on shutdown. */
    public void restartActiveGenerationSegmentForReplay() {
        if (!activeGenerationSegment.isEmpty() && activeGenerationCursor != 0) {
            activeGenerationCursor = 0;
            setDirty();
        }
    }

    void clearGenerationProgress() {
        generationPlanFingerprint = "";
        generationNetherrackEnabled = false;
        generationNetherrackInterval = PublicMineConfig.NETHERRACK_INTERVAL_MIN;
        pendingGenerationSegments.clear();
        completedGenerationSegments.clear();
        activeGenerationSegment = "";
        activeGenerationCursor = 0;
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
