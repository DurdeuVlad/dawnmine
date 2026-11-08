package com.dwurdy.dawnmine.generation;

import com.dwurdy.dawnmine.generation.SegmentGeometry.BlockKind;
import com.dwurdy.dawnmine.generation.SegmentGeometry.BlockOperation;
import com.dwurdy.dawnmine.generation.SegmentGeometry.SegmentPlan;
import com.dwurdy.dawnmine.topology.HubLayout;
import com.dwurdy.dawnmine.topology.NodeFeature;
import com.dwurdy.dawnmine.topology.TopoBounds;
import com.dwurdy.dawnmine.topology.TopoEdge;
import com.dwurdy.dawnmine.topology.TopoNode;
import com.dwurdy.dawnmine.topology.TopoPos;
import com.dwurdy.dawnmine.topology.TopologyPlan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.util.RandomSource;

/**
 * Deterministic parametric motif placement for the post-dress pass.
 * Each applicable {@link MotifCatalog.Motif} re-rolls its own spacing,
 * chance, element counts, and positions from
 * {@code runSeed ^ hash(segmentId|motifId)}, so the same rule set yields
 * a different concrete layout every run while remaining the same family.
 *
 * <p>Motifs are additive only: they never emit OPEN cells, never
 * overwrite an occupied dress cell, never land inside another route's
 * two-high walk column (except walk-through rails), and never enter a
 * protected volume. Wall elements embed into solid rock beside
 * reachable air exactly like {@link OrePlacementPlanner} vein cells.
 */
public final class MotifPlanner {
    private static final Comparator<TopoPos> ORDER = Comparator.comparingInt(TopoPos::y)
            .thenComparingInt(TopoPos::z).thenComparingInt(TopoPos::x);
    private static final int[][] NEIGHBOURS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final int[][] WALL_NEIGHBOURS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};
    /** Same reachability band ore veins use so motif walls stay visible. */
    private static final int REACHABLE_AIR_BAND = 3;
    /** Fail-closed ceiling on ops a motif segment may emit; far above the
     * catalog's own worst case (64 motifs x 12 elements x bounded counts)
     * so a pathological plan dies at planning time, not at slice apply. */
    private static final int MAX_MOTIF_OPERATIONS = 16_384;
    private static final int WALL_HEIGHT_MIN = 1;
    private static final int WALL_HEIGHT_MAX = 4;
    /** How far a node's motif anchors may roam from its resolved center. */
    private static final int NODE_SPINE_RADIUS = 9;

    private MotifPlanner() { }

    /** Full composition for one {@code motif:<structural id>} segment:
     * resolves the structural spine, tags, protection, and clearance from
     * the topology exactly the way the ore pass does, then plans every
     * applicable motif. Pure on the loaded catalogs - replay-safe.
     * Verification/test seam only: reads the LIVE catalogs, so generation
     * callers must use the pinned overload below. */
    public static SegmentPlan planSegment(TopologyPlan topology, String structuralId,
                                          TopoPos arrival) {
        return planSegment(topology, structuralId, arrival,
                MotifCatalog.active().values(), OreCatalog.active(),
                LandmarkCatalog.definitions());
    }

    /** Planning under caller-pinned catalog snapshots: a run receipted
     * under one catalog state must finish under that same state even if a
     * datapack reload swaps the live catalogs mid-carve. */
    public static SegmentPlan planSegment(TopologyPlan topology, String structuralId,
                                          TopoPos arrival,
                                          java.util.Collection<MotifCatalog.Motif> motifSet,
                                          OreCatalog.OreCatalogSnapshot ores,
                                          Map<String, LandmarkCatalog.Definition> landmarkDefs) {
        TopoBounds bounds = topology.bounds();
        // Pin the network epoch BEFORE resolving the structural/dress
        // plans: those helpers read the shared cache, so they must see the
        // same landmark epoch the motif predicates resolve against.
        PassageNetwork network = PassageNetwork.of(topology, arrival, landmarkDefs);
        SegmentPlan structural = structuralPlan(topology, structuralId, arrival);
        SegmentPlan dress = SegmentGeometry.dress(topology, structuralId, bounds, arrival);
        List<SegmentPlan> plans = List.of(structural, dress);
        Set<String> tags = segmentTags(topology, structuralId);
        List<TopoPos> spine = spineFor(topology, structuralId, network, plans);
        boolean spineIsPath = structuralId.startsWith("edge:")
                || structuralId.startsWith("connector:arrival:");
        LandmarkLayout landmarks = network.landmarks();
        Set<TopoPos> pads = new HashSet<>();
        for (TopoNode node : topology.nodes()) {
            if (node.has(NodeFeature.CHECKPOINT)) {
                TopoPos center = network.center(node.id());
                if (center != null) {
                    for (int dz = -2; dz <= 2; dz++) {
                        for (int dx = -2; dx <= 2; dx++) {
                            for (int dy = -1; dy <= 2; dy++) {
                                pads.add(center.offset(dx, dy, dz));
                            }
                        }
                    }
                }
            }
        }
        Set<TopoPos> clearance = SegmentGeometry.wholeRunClearance(topology, arrival);
        return planMotifSegment("motif:" + structuralId, plans,
                motifSet, tags, spine, spineIsPath,
                ores, pos -> OreCatalog.Tier.parse(regionForY(pos.y())),
                topology.seed(), bounds, clearance,
                pos -> SegmentGeometry.isArrivalKeepOut(pos, arrival)
                        || HubLayout.isReserved(pos)
                        || landmarks.isReserved(pos)
                        || pads.contains(pos));
    }

    /**
     * Plans all applicable motifs for one structural segment.
     *
     * @param motifSegmentId the {@code motif:<structural id>} segment id
     * @param plans          structural plan plus its dress plan so motifs
     *                       can see carved air, floors, and supports
     * @param motifs         catalog snapshot values (sorted internally)
     * @param segmentTags    applicability tags of the structural segment
     * @param spine          ordered candidate cells: the passage
     *                       centerline for edges, or room-floor cells for
     *                       nodes (expanded when {@code spineIsPath} is
     *                       false and the spine is a lone center)
     * @param spineIsPath    whether spine cells are walkable path cells
     * @param ores           active ore catalog for {@code ore_slot}
     * @param tierAt         depth tier for a candidate position
     * @param runSeed        shared run seed
     * @param bounds         owned planning bounds; ops outside are dropped
     * @param clearance      whole-run 2-high walk cells; only rails pass
     * @param isProtected    keepout predicate (arrival, hub, reserves, pads)
     */
    public static SegmentPlan planMotifSegment(String motifSegmentId,
            List<SegmentPlan> plans, java.util.Collection<MotifCatalog.Motif> motifs,
            Set<String> segmentTags, List<TopoPos> spine, boolean spineIsPath,
            OreCatalog.OreCatalogSnapshot ores,
            Function<TopoPos, OreCatalog.Tier> tierAt, long runSeed,
            TopoBounds bounds, Set<TopoPos> clearance, Predicate<TopoPos> isProtected) {
        Set<TopoPos> open = new HashSet<>();
        Set<TopoPos> occupied = new HashSet<>();
        Map<Long, Integer> floorY = new HashMap<>();
        for (SegmentPlan plan : plans) {
            for (BlockOperation operation : plan.operations()) {
                TopoPos pos = operation.position();
                if (operation.kind() == BlockKind.OPEN) {
                    open.add(pos);
                    floorY.merge(columnKey(pos.x(), pos.z()), pos.y() - 1, Math::min);
                } else {
                    occupied.add(pos);
                }
            }
        }
        List<TopoPos> candidates = new ArrayList<>(spine);
        if (!spineIsPath && spine.size() <= 4) {
            // A node's lone center expands to its room's floor-adjacent
            // open cells so node motifs can actually place somewhere.
            Set<TopoPos> roomFloor = new HashSet<>();
            for (TopoPos center : spine) {
                for (TopoPos cell : open) {
                    if (Math.abs(cell.x() - center.x()) <= NODE_SPINE_RADIUS
                            && Math.abs(cell.z() - center.z()) <= NODE_SPINE_RADIUS
                            && Math.abs(cell.y() - center.y()) <= 3
                            && !open.contains(cell.offset(0, -1, 0))) {
                        roomFloor.add(cell);
                    }
                }
            }
            candidates = new ArrayList<>(roomFloor);
        }
        candidates.sort(ORDER);
        Set<TopoPos> taken = new HashSet<>(occupied);
        List<BlockOperation> operations = new ArrayList<>();
        List<MotifCatalog.Motif> ordered = new ArrayList<>(motifs);
        ordered.sort(Comparator.comparing(MotifCatalog.Motif::id));
        for (MotifCatalog.Motif motif : ordered) {
            if (!motif.applies(segmentTags) || candidates.size() < 3) {
                continue;
            }
            RandomSource random = RandomSource.create(
                    runSeed ^ stableHash(motifSegmentId + "|" + motif.id()));
            MotifCatalog.Range spacingRange = motif.spacing();
            int spacing = spacingRange.min()
                    + random.nextInt(spacingRange.max() - spacingRange.min() + 1);
            int offset = random.nextInt(spacing);
            for (int i = 1; i < candidates.size() - 1; i++) {
                if ((i + offset) % spacing != 0) {
                    continue;
                }
                TopoPos anchor = candidates.get(i);
                if (!motif.regions().contains(tierAt.apply(anchor).id())
                        || random.nextDouble() >= motif.chance()) {
                    continue;
                }
                for (MotifCatalog.Element element : motif.elements()) {
                    if (random.nextDouble() >= element.chance()) {
                        continue;
                    }
                    int count = element.count().min() + random.nextInt(
                            element.count().max() - element.count().min() + 1);
                    place(element, count, anchor, i, candidates, open, occupied, taken,
                            clearance, bounds, isProtected, floorY, ores, tierAt,
                            random, operations);
                }
            }
        }
        if (operations.size() > MAX_MOTIF_OPERATIONS) {
            throw new IllegalArgumentException("segment " + motifSegmentId
                    + " exceeds " + MAX_MOTIF_OPERATIONS + " motif operations ("
                    + operations.size() + ") - the catalog ceiling is far lower;"
                    + " report this fixture");
        }
        operations.sort(Comparator.comparingInt((BlockOperation op) -> op.position().y())
                .thenComparingInt(op -> op.position().z())
                .thenComparingInt(op -> op.position().x()));
        return new SegmentPlan(motifSegmentId, operations);
    }

    /** One rolled element instance: picks its candidate pool by
     * offsetRule, arranges cells by element type, and emits ops through
     * the shared legality gate. */
    private static void place(MotifCatalog.Element element, int count, TopoPos anchor,
            int anchorIndex, List<TopoPos> spine, Set<TopoPos> open, Set<TopoPos> occupied,
            Set<TopoPos> taken, Set<TopoPos> clearance, TopoBounds bounds,
            Predicate<TopoPos> isProtected, Map<Long, Integer> floorY,
            OreCatalog.OreCatalogSnapshot ores,
            Function<TopoPos, OreCatalog.Tier> tierAt, RandomSource random,
            List<BlockOperation> operations) {
        switch (element.type()) {
            case PILLAR -> {
                List<TopoPos> pool = pool(element, anchor, open, occupied, taken,
                        clearance, bounds, isProtected, floorY);
                if (pool.isEmpty()) {
                    return;
                }
                TopoPos base = pool.get(random.nextInt(pool.size()));
                for (int dy = 0; dy < count; dy++) {
                    TopoPos cell = base.offset(0, dy, 0);
                    if (!open.contains(cell) || clearance.contains(cell) || taken.contains(cell)
                            || !bounds.contains(cell) || isProtected.test(cell)
                            || clearance.contains(cell.offset(0, 1, 0))) {
                        break;
                    }
                    emit(operations, taken, cell, element, ores, tierAt, random);
                }
            }
            case SCATTER -> {
                List<TopoPos> pool = pool(element, anchor, open, occupied, taken,
                        clearance, bounds, isProtected, floorY);
                for (int pick = 0; pick < count && !pool.isEmpty(); pick++) {
                    TopoPos cell = pool.remove(random.nextInt(pool.size()));
                    emit(operations, taken, cell, element, ores, tierAt, random);
                }
            }
            case VEIN -> {
                List<TopoPos> pool = pool(element, anchor, open, occupied, taken,
                        clearance, bounds, isProtected, floorY);
                if (pool.isEmpty()) {
                    return;
                }
                TopoPos start = pool.get(random.nextInt(pool.size()));
                Set<TopoPos> vein = new HashSet<>();
                vein.add(start);
                List<TopoPos> frontier = new ArrayList<>(List.of(start));
                while (vein.size() < count && !frontier.isEmpty()) {
                    TopoPos from = frontier.remove(random.nextInt(frontier.size()));
                    List<TopoPos> next = new ArrayList<>();
                    for (int[] d : WALL_NEIGHBOURS) {
                        TopoPos cell = from.offset(d[0], d[1], d[2]);
                        if (!vein.contains(cell) && pool.contains(cell)) {
                            next.add(cell);
                        }
                    }
                    if (next.isEmpty()) {
                        continue;
                    }
                    TopoPos cell = next.get(random.nextInt(next.size()));
                    vein.add(cell);
                    frontier.add(cell);
                }
                List<TopoPos> cells = new ArrayList<>(vein);
                cells.sort(ORDER);
                for (TopoPos cell : cells) {
                    emit(operations, taken, cell, element, ores, tierAt, random);
                }
            }
            case RAIL_STUB -> {
                TopoPos prev = null;
                for (int k = anchorIndex; k < Math.min(spine.size(), anchorIndex + count); k++) {
                    TopoPos cell = spine.get(k);
                    if (taken.contains(cell) || !bounds.contains(cell) || isProtected.test(cell)
                            || (prev != null && !adjacent(prev, cell))) {
                        break;
                    }
                    emit(operations, taken, cell, element, ores, tierAt, random);
                    prev = cell;
                }
            }
            case LAMP -> {
                List<TopoPos> pool = pool(element, anchor, open, occupied, taken,
                        clearance, bounds, isProtected, floorY);
                if (!pool.isEmpty()) {
                    emit(operations, taken, pool.get(random.nextInt(pool.size())),
                            element, ores, tierAt, random);
                }
            }
        }
    }

    /** Candidate cells for one element inside its radius of the anchor.
     * WALL embeds into rock beside reachable open air; FLOOR stands on
     * floor-adjacent open air outside the walk column; PATH follows the
     * spine itself. */
    private static List<TopoPos> pool(MotifCatalog.Element element, TopoPos anchor,
            Set<TopoPos> open, Set<TopoPos> occupied, Set<TopoPos> taken,
            Set<TopoPos> clearance, TopoBounds bounds, Predicate<TopoPos> isProtected,
            Map<Long, Integer> floorY) {
        List<TopoPos> pool = new ArrayList<>();
        int r = element.radius();
        switch (element.offset()) {
            case WALL -> {
                for (TopoPos cell : cellsNear(anchor, r, open, floorY)) {
                    Integer floor = floorY.get(columnKey(cell.x(), cell.z()));
                    if (floor == null || cell.y() - floor < WALL_HEIGHT_MIN
                            || cell.y() - floor > WALL_HEIGHT_MAX
                            || taken.contains(cell) || occupied.contains(cell)
                            || !bounds.contains(cell) || isProtected.test(cell)
                            || clearance.contains(cell)
                            || clearance.contains(cell.offset(0, 1, 0))) {
                        continue; // never overwrite or undercut any walk cell
                    }
                    pool.add(cell);
                }
            }
            case FLOOR -> {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        for (int dy = -2; dy <= 2; dy++) {
                            TopoPos cell = anchor.offset(dx, dy, dz);
                            if (open.contains(cell) && !open.contains(cell.offset(0, -1, 0))
                                    && !clearance.contains(cell) && !taken.contains(cell)
                                    && !occupied.contains(cell) && bounds.contains(cell)
                                    && !isProtected.test(cell)
                                    && !clearance.contains(cell.offset(0, 1, 0))) {
                                pool.add(cell);
                            }
                        }
                    }
                }
            }
            case PATH -> { /* handled by RAIL_STUB over the spine directly */ }
        }
        pool.sort(ORDER);
        return pool;
    }

    /** Rock cells near the anchor that directly neighbour reachable open
     * air - the same candidacy ore veins use, minus the ore write rule. */
    private static List<TopoPos> cellsNear(TopoPos anchor, int radius, Set<TopoPos> open,
                                           Map<Long, Integer> floorY) {
        Set<TopoPos> found = new HashSet<>();
        for (TopoPos air : open) {
            Integer airFloor = floorY.get(columnKey(air.x(), air.z()));
            if (airFloor == null || air.y() - airFloor > REACHABLE_AIR_BAND) {
                continue;
            }
            if (Math.abs(air.x() - anchor.x()) > radius + 1
                    || Math.abs(air.z() - anchor.z()) > radius + 1
                    || Math.abs(air.y() - anchor.y()) > radius + 1) {
                continue;
            }
            for (int[] d : NEIGHBOURS) {
                TopoPos rock = air.offset(d[0], d[1], d[2]);
                if (Math.abs(rock.x() - anchor.x()) <= radius
                        && Math.abs(rock.z() - anchor.z()) <= radius
                        && Math.abs(rock.y() - anchor.y()) <= radius
                        && !open.contains(rock)) {
                    found.add(rock);
                }
            }
        }
        return new ArrayList<>(found);
    }

    private static void emit(List<BlockOperation> operations, Set<TopoPos> taken,
            TopoPos cell, MotifCatalog.Element element,
            OreCatalog.OreCatalogSnapshot ores, Function<TopoPos, OreCatalog.Tier> tierAt,
            RandomSource random) {
        if (taken.contains(cell)) {
            return;
        }
        if (element.slot() == MotifCatalog.BlockSlot.ORE_SLOT) {
            OreCatalog.Entry entry = ores.choose(tierAt.apply(cell), random);
            if (entry == null) {
                return;
            }
            taken.add(cell);
            operations.add(new BlockOperation(cell, BlockKind.ORE, entry.blockId().toString()));
            return;
        }
        taken.add(cell);
        operations.add(new BlockOperation(cell, kindFor(element.slot())));
    }

    private static BlockKind kindFor(MotifCatalog.BlockSlot slot) {
        return switch (slot) {
            case SUPPORT -> BlockKind.TIMBER;
            case RUBBLE -> BlockKind.ROCK;
            case PLANKS -> BlockKind.PLANKS;
            case FENCE -> BlockKind.FENCE;
            case RAIL -> BlockKind.RAIL;
            case LIGHT -> BlockKind.LIGHT;
            case ORE_SLOT -> BlockKind.ORE;
        };
    }

    private static SegmentPlan structuralPlan(TopologyPlan topology, String structuralId,
                                              TopoPos arrival) {
        if (structuralId.startsWith("node:")) {
            TopoNode node = topology.node(structuralId.substring("node:".length()));
            if (node == null) {
                throw new IllegalArgumentException("unknown node in motif segment: " + structuralId);
            }
            return SegmentGeometry.node(topology, node, topology.bounds(), arrival);
        }
        if (structuralId.startsWith("edge:")) {
            TopoEdge edge = SegmentGeometry.edgeFromSegmentId(topology, structuralId);
            if (edge == null) {
                throw new IllegalArgumentException("unknown edge in motif segment: " + structuralId);
            }
            return SegmentGeometry.edge(topology, edge, topology.bounds(), arrival);
        }
        if (structuralId.equals(SegmentGeometry.connectorSegmentId(topology.entranceId()))) {
            return SegmentGeometry.entryConnector(topology, topology.bounds(), arrival);
        }
        throw new IllegalArgumentException("unknown segment id in motif segment: " + structuralId);
    }

    private static Set<String> segmentTags(TopologyPlan topology, String structuralId) {
        Set<String> tags = new HashSet<>();
        if (structuralId.startsWith("edge:")) {
            TopoEdge edge = SegmentGeometry.edgeFromSegmentId(topology, structuralId);
            tags.add("edge");
            if (edge != null) {
                tags.add(edge.kind().name().toLowerCase(java.util.Locale.ROOT));
                tags.add(SegmentGeometry.isMineshaft(structuralId, topology.seed(), edge.kind())
                        ? "mineshaft" : "natural");
            }
        } else if (structuralId.startsWith("node:")) {
            TopoNode node = topology.node(structuralId.substring("node:".length()));
            tags.add("node");
            if (node != null) {
                tags.add(node.kind().name().toLowerCase(java.util.Locale.ROOT));
            }
        } else {
            tags.add("connector");
        }
        return tags;
    }

    private static List<TopoPos> spineFor(TopologyPlan topology, String structuralId,
                                          PassageNetwork network,
                                          List<SegmentPlan> plans) {
        if (structuralId.startsWith("edge:")) {
            TopoEdge edge = SegmentGeometry.edgeFromSegmentId(topology, structuralId);
            return edge == null ? List.of() : SegmentGeometry.passageCenterline(topology, edge);
        }
        if (structuralId.startsWith("node:")) {
            TopoPos resolved = network.center(structuralId.substring("node:".length()));
            return resolved == null ? List.of() : List.of(resolved);
        }
        // The arrival connector's spine is its resolved centerline - a
        // fabricated meander would anchor motifs along a path that never
        // gets carved.
        return network.paths().getOrDefault(
                "connector:arrival:" + topology.entranceId(), List.of());
    }

    private static String regionForY(int y) {
        String found = "upper";
        String lastKnownTier = "upper";
        for (var region : com.dwurdy.dawnmine.topology.TopologyProfiles.active().regions()) {
            if (OreCatalog.Tier.isKnown(region.id())) {
                lastKnownTier = region.id();
            }
            if (y <= region.topY() && y >= region.bottomY()) {
                // A custom interstitial band (e.g. "transition") resolves
                // to the nearest required tier above it rather than
                // throwing inside the motif pass mid-generation.
                found = OreCatalog.Tier.isKnown(region.id())
                        ? region.id() : lastKnownTier;
            }
        }
        return found;
    }

    /** Orthogonal/diagonal adjacency at rail height - a rail stub only
     * continues while each next spine cell touches the previous one, so a
     * sorted-but-scattered room spine never lays floating singletons. */
    private static boolean adjacent(TopoPos a, TopoPos b) {
        return Math.abs(a.x() - b.x()) <= 1 && Math.abs(a.z() - b.z()) <= 1
                && a.y() == b.y() && !a.equals(b);
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFF_FFFFL);
    }

    private static long stableHash(String text) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
