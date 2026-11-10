package com.dwurdy.dawnmine.generation;

import com.dwurdy.dawnmine.topology.NodeKind;
import com.dwurdy.dawnmine.topology.TopoBounds;
import com.dwurdy.dawnmine.topology.TopoNode;
import com.dwurdy.dawnmine.topology.TopoPos;
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
 * Deterministic native-ore placement for the post-structural ore pass.
 * Every segment (arrival connector, ordinary rooms, and passages) receives
 * visible veins; resource fields simply get more. The planner emits bare
 * ORE operations for candidate wall rock; the server adapter writes a vein
 * block only where the world still holds exposed natural rock, so later
 * structural merges can never bury a vein.
 */
public final class OrePlacementPlanner {
    private static final Comparator<TopoPos> ORDER = Comparator.comparingInt(TopoPos::y)
            .thenComparingInt(TopoPos::z).thenComparingInt(TopoPos::x);
    /** Veins per ordinary room/passage (and the first descent). */
    public static final int ORDINARY_VEINS = 3;
    /** Veins per resource field - richer, not the sole source of ore. */
    public static final int RESOURCE_FIELD_VEINS = 8;
    /** Jackpot cavern nodes chosen per region - rare on purpose. */
    public static final int JACKPOT_NODES_PER_REGION = 1;
    /** Jackpot vein target as a multiple of the resource-field target. */
    public static final int JACKPOT_VEIN_MULTIPLIER = 4;
    /** Roughly one vein per this many centerline blocks in passages. */
    public static final int VEIN_PATH_SPACING = 12;
    /** Wall heights (blocks above the local carved floor) that may host ore. */
    public static final int WALL_HEIGHT_MIN = 1;
    public static final int WALL_HEIGHT_MAX = 4;
    /** How far above the local floor an adjacent open cell may sit and still
     * count as "reachable air" for wall candidacy (floor + 2-high walk + 1). */
    private static final int REACHABLE_AIR_BAND = 3;

    private OrePlacementPlanner() { }

    /** Vein target for a passage segment, scaled with its centerline length. */
    public static int passageVeinTarget(int centerlineLength) {
        return Math.max(ORDINARY_VEINS, centerlineLength / VEIN_PATH_SPACING);
    }

    /** Vein target for a jackpot node - a multiple of the resource-field
     * budget so the moment reads as visibly richer, never a marginal bump. */
    public static int jackpotVeinTarget(int base) {
        return base * JACKPOT_VEIN_MULTIPLIER;
    }

    /**
     * Selects the jackpot nodes for one run: exactly
     * {@link #JACKPOT_NODES_PER_REGION} {@link NodeKind#CAVERN} nodes per
     * region, ranked by a seeded hash of each node id the same way
     * {@code SegmentGeometry} splits mineshaft vs. natural passage style.
     * Pure function of the node set and the seed - deterministic replay.
     */
    public static Set<String> jackpotNodeIds(java.util.Collection<TopoNode> nodes, long seed) {
        Map<String, List<TopoNode>> byRegion = new java.util.TreeMap<>();
        for (TopoNode node : nodes) {
            if (node.kind() == NodeKind.CAVERN) {
                byRegion.computeIfAbsent(node.region(), k -> new ArrayList<>()).add(node);
            }
        }
        Set<String> ids = new HashSet<>();
        for (Map.Entry<String, List<TopoNode>> region : byRegion.entrySet()) {
            region.getValue().stream()
                    .sorted(Comparator.comparingLong(
                            (TopoNode n) -> jackpotScore(seed, region.getKey(), n.id())).reversed()
                            .thenComparing(TopoNode::id))
                    .limit(JACKPOT_NODES_PER_REGION)
                    .forEach(node -> ids.add(node.id()));
        }
        return ids;
    }

    private static long jackpotScore(long seed, String regionId, String nodeId) {
        return mix(seed ^ stableHash("jackpot:" + regionId + ":" + nodeId));
    }

    private static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    /**
     * Plans the ore segment for one already-planned structural segment.
     *
     * <p>Candidate rule: a rock cell is eligible when it directly neighbours
     * carved air that belongs to the reachable walk band - the air cell sits
     * no more than {@link #REACHABLE_AIR_BAND} above the local floor (the
     * lowest carved cell in its column minus one) - and the rock cell itself
     * is 1-4 blocks above that floor. Floor, ceiling, out-of-bounds, and
     * arrival/checkpoint-protected cells are discarded in planning, never at
     * mutation time.
     *
     * @param oreSegmentId the {@code ores:<structural id>} segment id
     * @param plans        structural plan (optionally plus its dressing plan)
     *                     so ore never lands on air, floors, or decorations
     * @param catalog      active ore catalog snapshot
     * @param tierAt       depth tier for a candidate position
     * @param veinTarget   how many veins to attempt; a candidate-starved
     *                     segment simply produces fewer, never an error
     * @param runSeed      shared run seed
     * @param bounds       owned planning bounds; candidates outside are dropped
     * @param isProtected  keepout predicate (arrival pad, checkpoint pads)
     */
    public static SegmentGeometry.SegmentPlan planOreSegment(String oreSegmentId,
            List<SegmentGeometry.SegmentPlan> plans,
            OreCatalog.OreCatalogSnapshot catalog,
            Function<TopoPos, OreCatalog.Tier> tierAt,
            int veinTarget, long runSeed,
            TopoBounds bounds, Predicate<TopoPos> isProtected) {
        Set<TopoPos> open = new HashSet<>();
        Set<TopoPos> occupied = new HashSet<>();
        Map<Long, Integer> floorY = new HashMap<>();
        for (SegmentGeometry.SegmentPlan plan : plans) {
            for (SegmentGeometry.BlockOperation operation : plan.operations()) {
                TopoPos pos = operation.position();
                if (operation.kind() == SegmentGeometry.BlockKind.OPEN) {
                    open.add(pos);
                    floorY.merge(columnKey(pos.x(), pos.z()), pos.y() - 1, Math::min);
                } else if (operation.kind() != SegmentGeometry.BlockKind.WALL_SKIN) {
                    // A wall/ceiling/pillar skin is cosmetic default-rock
                    // texture, not a structural claim on the cell - ore and
                    // motifs must stay free to embed into it exactly as if
                    // it were untouched natural rock.
                    occupied.add(pos);
                }
            }
        }
        Set<TopoPos> seen = new HashSet<>();
        List<TopoPos> candidates = new ArrayList<>();
        for (TopoPos openCell : open) {
            Integer floor = floorY.get(columnKey(openCell.x(), openCell.z()));
            if (floor == null || openCell.y() - floor > REACHABLE_AIR_BAND) {
                continue; // air above the reachable band (ceiling pockets)
            }
            for (int[] offset : NEIGHBOURS) {
                TopoPos rockPos = openCell.offset(offset[0], offset[1], offset[2]);
                if (open.contains(rockPos) || occupied.contains(rockPos)
                        || !bounds.contains(rockPos) || isProtected.test(rockPos)
                        || !seen.add(rockPos)) {
                    continue;
                }
                int wallHeight = rockPos.y() - floor;
                if (wallHeight < WALL_HEIGHT_MIN || wallHeight > WALL_HEIGHT_MAX) {
                    continue;
                }
                candidates.add(rockPos);
            }
        }
        candidates.sort(ORDER);
        if (candidates.isEmpty() || veinTarget <= 0) {
            return new SegmentGeometry.SegmentPlan(oreSegmentId, List.of());
        }

        long segmentSeed = runSeed ^ stableHash(oreSegmentId);
        RandomSource random = RandomSource.create(segmentSeed);
        Set<TopoPos> placed = new HashSet<>();
        List<SegmentGeometry.BlockOperation> operations = new ArrayList<>();
        int veins = Math.min(veinTarget, candidates.size());
        for (int vein = 0; vein < veins; vein++) {
            List<TopoPos> available = new ArrayList<>();
            for (TopoPos candidate : candidates) {
                if (!placed.contains(candidate)) {
                    available.add(candidate);
                }
            }
            if (available.isEmpty()) {
                break;
            }
            TopoPos start = available.get(random.nextInt(available.size()));
            OreCatalog.Tier tier = tierAt.apply(start);
            OreCatalog.Entry entry = catalog.choose(tier, random);
            if (entry == null) {
                continue;
            }
            for (TopoPos pos : clusterFrom(start, entry.maxVeinSize(), candidates, placed, random)) {
                placed.add(pos);
                operations.add(new SegmentGeometry.BlockOperation(pos,
                        SegmentGeometry.BlockKind.ORE, entry.blockId().toString()));
            }
        }
        operations.sort(Comparator.comparingInt((SegmentGeometry.BlockOperation operation) ->
                        operation.position().y())
                .thenComparingInt(operation -> operation.position().z())
                .thenComparingInt(operation -> operation.position().x()));
        return new SegmentGeometry.SegmentPlan(oreSegmentId, operations);
    }

    /** Compatibility overload for tests without protection plumbing. */
    public static SegmentGeometry.SegmentPlan planOreSegment(String oreSegmentId,
            List<SegmentGeometry.SegmentPlan> plans,
            OreCatalog.OreCatalogSnapshot catalog,
            Function<TopoPos, OreCatalog.Tier> tierAt,
            int veinTarget, long runSeed, TopoBounds bounds) {
        return planOreSegment(oreSegmentId, plans, catalog, tierAt, veinTarget,
                runSeed, bounds, pos -> false);
    }

    private static List<TopoPos> clusterFrom(TopoPos start, int maxSize, List<TopoPos> candidates,
                                             Set<TopoPos> alreadyPlaced, RandomSource random) {
        List<TopoPos> cluster = new ArrayList<>();
        cluster.add(start);
        while (cluster.size() < maxSize) {
            List<TopoPos> adjacent = new ArrayList<>();
            for (TopoPos pos : candidates) {
                if (alreadyPlaced.contains(pos) || cluster.contains(pos)) {
                    continue;
                }
                for (TopoPos existing : cluster) {
                    if (manhattan(existing, pos) == 1) {
                        adjacent.add(pos);
                        break;
                    }
                }
            }
            if (adjacent.isEmpty()) {
                break;
            }
            adjacent.sort(ORDER);
            cluster.add(adjacent.get(random.nextInt(adjacent.size())));
        }
        return cluster;
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

    private static int manhattan(TopoPos left, TopoPos right) {
        return Math.abs(left.x() - right.x()) + Math.abs(left.y() - right.y())
                + Math.abs(left.z() - right.z());
    }

    private static final int[][] NEIGHBOURS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
}
