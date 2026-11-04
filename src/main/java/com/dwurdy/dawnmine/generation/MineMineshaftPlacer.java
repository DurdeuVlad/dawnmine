package com.dwurdy.dawnmine.generation;

import com.dwurdy.dawnmine.config.DawnmineConfig;
import com.dwurdy.dawnmine.run.MineRunData;
import com.dwurdy.dawnmine.topology.HubLayout;
import com.dwurdy.dawnmine.topology.TopoEdge;
import com.dwurdy.dawnmine.topology.TopoNode;
import com.dwurdy.dawnmine.topology.TopoPos;
import com.dwurdy.dawnmine.topology.TopologyPlan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Places real vanilla mineshaft structures into the run's cave world.
 * Sites are sampled deterministically from the run seed and the run's
 * own topology (so shafts sit inside the adventure space), while the
 * shaft layout itself is seeded by the run seed, making shafts differ
 * per run. Natural placement stays off: the dawnmine biome is not in
 * any structure tag, so every shaft here is deliberate and bounded.
 * Placement is best-effort scenery - failures are skipped, never fatal.
 */
public final class MineMineshaftPlacer {
    private static final Logger LOGGER = LoggerFactory.getLogger("dawnmine");
    /** No shaft may overlap this central square; it contains the hub,
     * gateway band, arrival corridor and entrance apron. */
    private static final int PROTECTED_HALF = 48;
    /** Every placed structure must live fully below this Y - the run must
     * feel deep underground; nothing is allowed into the roof band. */
    private static final int DEEP_CEILING_Y = 64;
    /** Corridor keep-out margin: passage halfwidth reaches 6 blocks, so a
     * structure box must stay at least this clear of every centerline
     * cell to guarantee it cannot hollow the walkable volume. */
    private static final int CORRIDOR_MARGIN = 8;
    private static final long SITE_SALT = 0x4D53484654L; // "MSHFT"

    /** Structures deliberately placed per run: vanilla mineshafts plus
     * underground-capable dungeons shipped by the installed modpack.
     * Natural placement stays off (the dawnmine biome is in no structure
     * tag), so every site here is bounded, seeded and intended.
     * horror_element_mod:labyrinth was tried and anchors above the deep
     * ceiling, so it is deliberately excluded. */
    private record SiteSpec(String structureId, int count) { }

    /** The optional-integration surface: 'modid:structure_id=N' config
     * entries. Entries whose mod is absent log "not registered" and are
     * skipped, so pack owners can enable any mod structure by config. */
    private static List<SiteSpec> configuredSites() {
        List<SiteSpec> specs = new ArrayList<>();
        for (String entry : DawnmineConfig.structureSites()) {
            int sep = entry.lastIndexOf('=');
            try {
                specs.add(new SiteSpec(entry.substring(0, sep).trim(),
                        Math.max(0, Math.min(32, Integer.parseInt(entry.substring(sep + 1).trim())))));
            } catch (RuntimeException e) {
                LOGGER.warn("[dawnmine] Ignoring malformed structureSites entry '{}'.", entry);
            }
        }
        return specs;
    }

    private MineMineshaftPlacer() { }

    public static int place(MinecraftServer server, ServerLevel mine,
                            MineRunData data, TopologyPlan topology) {
        if (!(mine.getChunkSource().getGenerator() instanceof RunSeedNoiseLevelSource noise)) {
            return 0;
        }
        // Placement is once per run: persisted receipts make restarts
        // idempotent for blocks AND side effects (entities, chest loot,
        // player edits inside placed volumes).
        for (MineRunData.PlacedStructure receipt : data.placedStructures()) {
            if (receipt.runId().equals(data.runId())) {
                LOGGER.info("[dawnmine] Optional structures already placed for run {} ({} receipt(s)); skipping.",
                        data.runId(), data.placedStructures().size());
                return 0;
            }
        }
        net.minecraft.core.Registry<Structure> structures = server.registryAccess()
                .registryOrThrow(Registries.STRUCTURE);
        List<TopoNode> candidates = new ArrayList<>(topology.nodes());
        List<BoundingBox> claimedBoxes = new ArrayList<>();
        PassageNetwork network = PassageNetwork.of(topology, HubLayout.LANDING);
        Map<ChunkPos, List<TopoPos>> corridorIndex = corridorIndex(topology, network);
        Map<ChunkPos, List<BlockPos>> oreIndex = oreIndex(mine, data);
        candidates.sort((a, b) -> a.id().compareTo(b.id()));
        int placed = 0;
        int specIndex = 0;
        try {
        for (SiteSpec spec : configuredSites()) {
            Structure structure;
            try {
                structure = structures.get(ResourceLocation.parse(spec.structureId()));
            } catch (RuntimeException e) {
                LOGGER.warn("[dawnmine] Ignoring malformed structure id '{}' for run {}.",
                        spec.structureId(), data.runId());
                continue;
            }
            if (structure == null) {
                LOGGER.info("[dawnmine] Skipping structure {} for run {}: not registered "
                        + "(mod not present).", spec.structureId(), data.runId());
                continue;
            }
            RandomSource picker = RandomSource.create(data.seed() ^ (SITE_SALT + specIndex++));
            int specPlaced = 0;
            int attempts = 0;
            List<TopoNode> pool = new ArrayList<>(candidates);
            while (specPlaced < spec.count() && attempts++ < pool.size() * 2 && !pool.isEmpty()) {
                TopoNode node = pool.remove(picker.nextInt(pool.size()));
                ChunkPos site = new ChunkPos(new BlockPos(
                        node.pos().x(), node.pos().y(), node.pos().z()));
                StructureStart start;
                try {
                    start = structure.generate(
                            server.registryAccess(),
                            noise, noise.getBiomeSource(),
                            noise.runState(mine.getChunkSource().randomState()),
                            mine.getStructureManager(), data.seed(), site, 0, mine,
                            biome -> true);
                } catch (RuntimeException e) {
                    LOGGER.warn("[dawnmine] Structure {} failed to evaluate at {} for run {}.",
                            spec.structureId(), site, data.runId(), e);
                    continue;
                }
                if (!start.isValid()) {
                    continue;
                }
                BoundingBox box = start.getBoundingBox();
                if (!insideMine(box, noise.shellRadiusChunks()) || overlapsProtected(box)
                        || box.maxY() > DEEP_CEILING_Y
                        || overlapsKeepouts(mine, box, topology, data, network, claimedBoxes,
                                corridorIndex, oreIndex)) {
                    continue;
                }
                // Claim the volume before writing: if placement throws
                // mid-write the half-built structure must still block later
                // candidates and feed ore reconciliation.
                claimedBoxes.add(box);
                ChunkPos min = new ChunkPos(box.minX() >> 4, box.minZ() >> 4);
                ChunkPos max = new ChunkPos(box.maxX() >> 4, box.maxZ() >> 4);
                try {
                    ChunkPos.rangeClosed(min, max).forEach(chunk -> start.placeInChunk(
                            mine, mine.structureManager(), noise, picker.fork(),
                            new BoundingBox(
                                    chunk.getMinBlockX(), mine.getMinBuildHeight(), chunk.getMinBlockZ(),
                                    chunk.getMaxBlockX(), mine.getMaxBuildHeight() - 1, chunk.getMaxBlockZ()),
                            chunk));
                } catch (RuntimeException e) {
                    LOGGER.warn("[dawnmine] Structure {} failed while writing at {} for run {}.",
                            spec.structureId(), site, data.runId(), e);
                    continue;
                }
                placed++;
                specPlaced++;
                data.addPlacedStructure(spec.structureId(), box);
                LOGGER.info("[dawnmine] Placed {} for run {} at chunk {} (box {})",
                        spec.structureId(), data.runId(), site, box);
            }
            if (specPlaced < spec.count()) {
                LOGGER.info("[dawnmine] Only {} of {} {} sites placed for run {}.",
                        specPlaced, spec.count(), spec.structureId(), data.runId());
            }
        }
        } finally {
            reconcileOreOrigins(mine, data, claimedBoxes);
        }
        return placed;
    }

    /** Shaft carving runs after ore placement and can hollow planned ore
     * blocks to cave_air. Drop the provenance record when the block no
     * longer holds its recorded ore so audits stay truthful. */
    private static void reconcileOreOrigins(ServerLevel mine, MineRunData data,
                                            List<BoundingBox> placedBoxes) {
        if (placedBoxes.isEmpty()) {
            return;
        }
        int pruned = 0;
        for (MineRunData.MineOreOrigin origin : data.mineOreOrigins()) {
            if (!origin.dimension().equals(mine.dimension())
                    || !origin.runId().equals(data.runId())) {
                continue;
            }
            BlockPos p = origin.pos();
            boolean inside = false;
            for (BoundingBox box : placedBoxes) {
                if (box.isInside(p)) {
                    inside = true;
                    break;
                }
            }
            if (!inside) {
                continue;
            }
            if (!net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(mine.getBlockState(p).getBlock()).equals(origin.blockId())
                    && data.removeMineOre(mine.dimension(), p)) {
                pruned++;
            }
        }
        if (pruned > 0) {
            LOGGER.info("[dawnmine] Pruned {} ore origin record(s) carved away by mineshafts in run {}.",
                    pruned, data.runId());
        }
    }

    private static boolean insideMine(BoundingBox box, int shellRadiusChunks) {
        int bound = MineShellGeometry.maxBlock(shellRadiusChunks) - 16;
        int min = MineShellGeometry.minBlock(shellRadiusChunks) + 16;
        return box.minX() >= min && box.maxX() <= bound
                && box.minZ() >= min && box.maxZ() <= bound;
    }

    /** "No generation cut": a placement is rejected if it overlaps any
     * generated space the run guarantees intact - every node's room
     * volume, all recorded safe zones and checkpoint stations, and every
     * previously placed structure's box (structures must not cut each
     * other either). Corridor centerlines are protected cell-by-cell so no
     * structure can hollow a route between rooms. Ore provenance stays
     * reconciled as a backstop. */
    private static boolean overlapsKeepouts(ServerLevel mine, BoundingBox box,
                                            TopologyPlan topology, MineRunData data,
                                            PassageNetwork network,
                                            List<BoundingBox> claimedBoxes,
                                            Map<ChunkPos, List<TopoPos>> corridorIndex,
                                            Map<ChunkPos, List<BlockPos>> oreIndex) {
        int x0 = (box.minX() - CORRIDOR_MARGIN) >> 4;
        int x1 = (box.maxX() + CORRIDOR_MARGIN) >> 4;
        int z0 = (box.minZ() - CORRIDOR_MARGIN) >> 4;
        int z1 = (box.maxZ() + CORRIDOR_MARGIN) >> 4;
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                List<TopoPos> cells = corridorIndex.get(new ChunkPos(cx, cz));
                if (cells == null) {
                    continue;
                }
                for (TopoPos cell : cells) {
                    if (overlaps(box,
                            cell.x() - CORRIDOR_MARGIN, cell.y() - 3, cell.z() - CORRIDOR_MARGIN,
                            cell.x() + CORRIDOR_MARGIN, cell.y() + 9, cell.z() + CORRIDOR_MARGIN)) {
                        return true;
                    }
                }
            }
        }
        for (TopoNode node : topology.nodes()) {
            TopoPos resolved = network.center(node.id());
            TopoPos center = resolved != null ? resolved : node.pos();
            int r = SegmentGeometry.roomCarveHalfWidth(node.kind());
            int v = SegmentGeometry.roomCarveHalfHeight(node.kind());
            if (overlaps(box, center.x() - r, center.y() - v, center.z() - r,
                    center.x() + r, center.y() + v, center.z() + r)) {
                return true;
            }
        }
        for (MineRunData.SafeZone zone : data.safeZones()) {
            if (zone.volume().dimension().equals(mine.dimension())
                    && overlapsVolume(box, zone.volume())) {
                return true;
            }
        }
        for (MineRunData.CheckpointStation station : data.checkpointStations()) {
            if (station.safeVolume().dimension().equals(mine.dimension())
                    && overlapsVolume(box, station.safeVolume())) {
                return true;
            }
        }
        for (Map.Entry<ChunkPos, List<BlockPos>> e : oreIndex.entrySet()) {
            ChunkPos c = e.getKey();
            if (c.getMinBlockX() > box.maxX() || c.getMaxBlockX() < box.minX()
                    || c.getMinBlockZ() > box.maxZ() || c.getMaxBlockZ() < box.minZ()) {
                continue;
            }
            for (BlockPos ore : e.getValue()) {
                if (box.isInside(ore)) {
                    return true;
                }
            }
        }
        for (BoundingBox placed : claimedBoxes) {
            if (box.intersects(placed)) {
                return true;
            }
        }
        return false;
    }

    /** Chunk-indexed map of every corridor centerline cell in the run, so
     * a candidate box only tests cells in chunks it could touch. Covers
     * all topology edges, including hub connectors and detour loops. */
    private static Map<ChunkPos, List<TopoPos>> corridorIndex(TopologyPlan topology,
                                                            PassageNetwork network) {
        Map<ChunkPos, List<TopoPos>> index = new HashMap<>();
        for (TopoEdge edge : topology.edges()) {
            for (TopoPos cell : network.path(SegmentGeometry.edgeSegmentId(edge))) {
                index.computeIfAbsent(new ChunkPos(cell.x() >> 4, cell.z() >> 4),
                        k -> new ArrayList<>()).add(cell);
            }
        }
        TopoNode entrance = topology.node(topology.entranceId());
        if (entrance != null
                && entrance.kind() == com.dwurdy.dawnmine.topology.NodeKind.ENTRANCE) {
            for (TopoPos cell : network.path("connector:arrival:" + topology.entranceId())) {
                index.computeIfAbsent(new ChunkPos(cell.x() >> 4, cell.z() >> 4),
                        k -> new ArrayList<>()).add(cell);
            }
        }
        int cells = 0;
        for (List<TopoPos> list : index.values()) {
            cells += list.size();
        }
        LOGGER.info("[dawnmine] Corridor keep-out indexed {} centerline cells across {} chunks.",
                cells, index.size());
        return index;
    }

    /** Chunk-indexed map of every recorded ore placement, so a structure
     * box containing an ore cell is rejected before it can hollow the ore. */
    private static Map<ChunkPos, List<BlockPos>> oreIndex(ServerLevel mine, MineRunData data) {
        Map<ChunkPos, List<BlockPos>> index = new HashMap<>();
        for (MineRunData.MineOreOrigin origin : data.mineOreOrigins()) {
            if (!origin.dimension().equals(mine.dimension())
                    || !origin.runId().equals(data.runId())) {
                continue;
            }
            BlockPos p = origin.pos();
            index.computeIfAbsent(new ChunkPos(p), k -> new ArrayList<>()).add(p);
        }
        return index;
    }

    private static boolean overlapsVolume(BoundingBox box,
                                          com.dwurdy.dawnmine.run.OwnedVolume volume) {
        return overlaps(box, volume.min().getX(), volume.min().getY(), volume.min().getZ(),
                volume.max().getX(), volume.max().getY(), volume.max().getZ());
    }

    private static boolean overlaps(BoundingBox box, int x0, int y0, int z0,
                                    int x1, int y1, int z1) {
        return box.minX() <= x1 && box.maxX() >= x0
                && box.minY() <= y1 && box.maxY() >= y0
                && box.minZ() <= z1 && box.maxZ() >= z0;
    }

    private static boolean overlapsProtected(BoundingBox box) {
        return box.minX() <= PROTECTED_HALF && box.maxX() >= -PROTECTED_HALF
                && box.minZ() <= PROTECTED_HALF && box.maxZ() >= -PROTECTED_HALF
                && box.minY() <= HubLayout.ROOF_TOP_Y + 1
                && box.maxY() >= HubLayout.INTERIOR_MIN_Y - 1;
    }
}
