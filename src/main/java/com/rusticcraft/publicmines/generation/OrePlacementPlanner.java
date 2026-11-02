package com.rusticcraft.publicmines.generation;

import com.rusticcraft.publicmines.topology.NodeFeature;
import com.rusticcraft.publicmines.topology.TopoNode;
import com.rusticcraft.publicmines.topology.TopoPos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.util.RandomSource;

/** Deterministic, sparse native-ore placement in explicitly planned resource fields. */
public final class OrePlacementPlanner {
    private static final Comparator<TopoPos> ORDER = Comparator.comparingInt(TopoPos::y)
            .thenComparingInt(TopoPos::z).thenComparingInt(TopoPos::x);
    private static final int VEINS_PER_FIELD = 2;

    private OrePlacementPlanner() { }

    public static SegmentGeometry.SegmentPlan addOres(TopoNode node,
            SegmentGeometry.SegmentPlan room, OreCatalog.OreCatalogSnapshot catalog, long runSeed) {
        if (!node.has(NodeFeature.RESOURCE_FIELD)) {
            return room;
        }
        OreCatalog.Tier tier = OreCatalog.Tier.parse(node.region());
        if (catalog.resourceEntries(tier).isEmpty()) {
            throw new IllegalStateException("ore catalog has no entries for depth tier " + tier.id());
        }
        Map<TopoPos, SegmentGeometry.BlockOperation> operations = new HashMap<>();
        for (SegmentGeometry.BlockOperation operation : room.operations()) {
            operations.put(operation.position(), operation);
        }
        Set<TopoPos> exposedRock = new HashSet<>();
        for (SegmentGeometry.BlockOperation operation : room.operations()) {
            if (operation.kind() == SegmentGeometry.BlockKind.OPEN) {
                for (int[] offset : NEIGHBOURS) {
                    TopoPos rockPos = operation.position().offset(offset[0], offset[1], offset[2]);
                    SegmentGeometry.BlockOperation neighbour = operations.get(rockPos);
                    if (neighbour != null && neighbour.kind() == SegmentGeometry.BlockKind.ROCK
                            && rockPos.y() >= node.pos().y()) {
                        exposedRock.add(rockPos);
                    }
                }
            }
        }
        List<TopoPos> candidates = exposedRock.stream().sorted(ORDER).toList();
        if (candidates.isEmpty()) {
            throw new IllegalStateException("resource field " + node.id() + " has no exposed rock wall");
        }

        long fieldSeed = runSeed ^ ((long) node.id().hashCode() << 32)
                ^ ((long) node.pos().x() * 0x9E3779B97F4A7C15L)
                ^ ((long) node.pos().y() * 0xC2B2AE3D27D4EB4FL)
                ^ ((long) node.pos().z() * 0x165667B19E3779F9L);
        RandomSource random = RandomSource.create(fieldSeed);
        Set<TopoPos> placed = new HashSet<>();
        for (int vein = 0; vein < VEINS_PER_FIELD && placed.size() < candidates.size(); vein++) {
            List<TopoPos> available = candidates.stream().filter(pos -> !placed.contains(pos)).toList();
            if (available.isEmpty()) {
                break;
            }
            TopoPos start = available.get(random.nextInt(available.size()));
            OreCatalog.Entry entry = catalog.choose(tier, random);
            List<TopoPos> cluster = clusterFrom(start, entry.maxVeinSize(), candidates, placed, random);
            for (TopoPos pos : cluster) {
                placed.add(pos);
                operations.put(pos, new SegmentGeometry.BlockOperation(pos,
                        SegmentGeometry.BlockKind.ORE, entry.blockId().toString()));
            }
        }

        List<SegmentGeometry.BlockOperation> sorted = operations.values().stream()
                .sorted(Comparator.comparingInt((SegmentGeometry.BlockOperation operation) ->
                                operation.position().y())
                        .thenComparingInt(operation -> operation.position().z())
                        .thenComparingInt(operation -> operation.position().x()))
                .toList();
        return new SegmentGeometry.SegmentPlan(room.id(), sorted);
    }

    private static List<TopoPos> clusterFrom(TopoPos start, int maxSize, List<TopoPos> candidates,
                                             Set<TopoPos> alreadyPlaced, RandomSource random) {
        List<TopoPos> cluster = new ArrayList<>();
        cluster.add(start);
        while (cluster.size() < maxSize) {
            List<TopoPos> adjacent = candidates.stream()
                    .filter(pos -> !alreadyPlaced.contains(pos) && !cluster.contains(pos))
                    .filter(pos -> cluster.stream().anyMatch(existing -> manhattan(existing, pos) == 1))
                    .sorted(ORDER).toList();
            if (adjacent.isEmpty()) {
                break;
            }
            cluster.add(adjacent.get(random.nextInt(adjacent.size())));
        }
        return cluster;
    }

    private static int manhattan(TopoPos left, TopoPos right) {
        return Math.abs(left.x() - right.x()) + Math.abs(left.y() - right.y())
                + Math.abs(left.z() - right.z());
    }

    private static final int[][] NEIGHBOURS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
}
