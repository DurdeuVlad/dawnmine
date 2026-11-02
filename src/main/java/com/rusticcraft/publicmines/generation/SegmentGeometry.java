package com.rusticcraft.publicmines.generation;

import com.rusticcraft.publicmines.topology.NodeFeature;
import com.rusticcraft.publicmines.topology.NodeKind;
import com.rusticcraft.publicmines.topology.TopoBounds;
import com.rusticcraft.publicmines.topology.TopoEdge;
import com.rusticcraft.publicmines.topology.TopoNode;
import com.rusticcraft.publicmines.topology.TopoPos;
import com.rusticcraft.publicmines.topology.TopologyPlan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure deterministic conversion of one topology node/edge into block
 * operations. It knows positions and semantic block kinds only; the server
 * adapter resolves block states and performs bounded writes.
 */
public final class SegmentGeometry {
    private static final int CORRIDOR_HALF_WIDTH = 2;
    private static final int CORRIDOR_FLOOR = -1;
    private static final int CORRIDOR_CEILING = 3;
    private static final int MAX_CENTERLINE_STEPS = 8192;
    private static final int MAX_OPERATIONS = 250_000;

    private static final Comparator<TopoPos> POSITION_ORDER = Comparator
            .comparingInt(TopoPos::y).thenComparingInt(TopoPos::z).thenComparingInt(TopoPos::x);

    private SegmentGeometry() {}

    /** Block palette-independent intent for a single deterministic write. */
    public enum BlockKind {
        ROCK,
        OPEN,
        HAZARD_BOUNDARY,
        HAZARD_SOURCE
    }

    public record BlockOperation(TopoPos position, BlockKind kind) {
        public BlockOperation {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(kind, "kind");
        }
    }

    private record RankedBlock(BlockKind kind, int priority) {}

    /** Immutable, stable-ordered operations. Cursor offsets count operations. */
    public record SegmentPlan(String id, List<BlockOperation> operations) {
        public SegmentPlan {
            Objects.requireNonNull(id, "id");
            operations = List.copyOf(operations);
        }

        public List<BlockOperation> operations(int cursor, int limit) {
            if (cursor < 0 || cursor > operations.size()) {
                throw new IllegalArgumentException("cursor outside segment: " + cursor);
            }
            if (limit < 0) {
                throw new IllegalArgumentException("operation limit must be non-negative");
            }
            int end = cursor + Math.min(limit, operations.size() - cursor);
            return operations.subList(cursor, end);
        }
    }

    /** Makes a room centered on the node, with enough floor/ceiling clearance
     * for walking. Checkpoint rooms get an explicit flat 5x5 landing pad. */
    public static SegmentPlan node(TopologyPlan topology, TopoNode node,
                                   TopoBounds bounds, TopoPos arrival) {
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(arrival, "arrival");
        TopoNode canonicalNode = topology.node(node.id());
        if (canonicalNode == null || !canonicalNode.equals(node)) {
            throw new IllegalArgumentException("node is not part of the supplied topology: " + node.id());
        }

        int radius = switch (node.kind()) {
            case ENTRANCE -> 4;
            case ROUTE -> 4;
            case CAVE -> 5;
            case CAVERN -> 8;
            case POCKET -> 3;
        };
        int innerY = node.kind() == NodeKind.CAVERN ? 4 : 2;
        int outerX = radius + 1;
        int outerY = innerY + 1;
        Map<TopoPos, RankedBlock> blocks = new HashMap<>();
        TopoPos center = node.pos();

        for (int dy = -outerY; dy <= outerY; dy++) {
            for (int dz = -outerX; dz <= outerX; dz++) {
                for (int dx = -outerX; dx <= outerX; dx++) {
                    double outer = normalized(dx, outerX) + normalized(dy, outerY)
                            + normalized(dz, outerX);
                    if (outer > 1.0) {
                        continue;
                    }
                    double inner = normalized(dx, radius) + normalized(dy, innerY)
                            + normalized(dz, radius);
                    put(blocks, center.offset(dx, dy, dz),
                            inner <= 1.0 ? BlockKind.OPEN : BlockKind.ROCK,
                            inner <= 1.0 ? 2 : 1);
                }
            }
        }

        if (node.has(NodeFeature.LANDMARK)) {
            addLandmark(blocks, center, radius);
        }
        addWalkableFloor(blocks, center, Math.min(3, radius - 1));
        if (node.has(NodeFeature.CHECKPOINT)) {
            addCheckpointPad(blocks, center);
        }
        if ("chaos".equals(node.region()) && node.kind() == NodeKind.CAVERN
                && !node.has(NodeFeature.CHECKPOINT)) {
            addContainedLavaPocket(blocks, center);
        }
        return finish("node:" + node.id(), blocks, bounds, arrival, false);
    }

    /** Makes the corridor tube connecting the two planned node centers. */
    public static SegmentPlan edge(TopologyPlan topology, TopoEdge edge,
                                   TopoBounds bounds, TopoPos arrival) {
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(edge, "edge");
        TopoNode a = topology.node(edge.a());
        TopoNode b = topology.node(edge.b());
        if (a == null || b == null) {
            throw new IllegalArgumentException("edge has an endpoint missing from topology: " + edge);
        }
        Map<TopoPos, RankedBlock> blocks = new HashMap<>();
        addTube(blocks, line(a.pos(), b.pos()));
        return finish("edge:" + edge.a() + ":" + edge.b(), blocks, bounds, arrival, false);
    }

    /**
     * Bootstrap connector from the outer edge of the intact M1 arrival pad to
     * the M2a entrance node. The first leg walks away from the pad while
     * descending one block per two horizontal blocks, then turns back at
     * entrance height. It never edits the arrival pad.
     */
    public static SegmentPlan entryConnector(TopologyPlan topology, TopoBounds bounds,
                                             TopoPos arrival) {
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(arrival, "arrival");
        TopoNode entrance = topology.node(topology.entranceId());
        if (entrance == null || entrance.kind() != NodeKind.ENTRANCE) {
            throw new IllegalArgumentException("topology has no valid entrance node");
        }

        TopoPos start = arrival.offset(3, 0, 0);
        int descent = start.y() - entrance.pos().y();
        if (descent < 1) {
            throw new IllegalArgumentException("entrance must be below the arrival pad");
        }
        if (entrance.pos().z() != start.z()) {
            throw new IllegalArgumentException("entrance must align with the arrival connector axis");
        }
        int horizontalRun = Math.max(2, Math.multiplyExact(descent, 2));
        int turningX = Math.addExact(start.x(), horizontalRun);
        Map<TopoPos, RankedBlock> blocks = new HashMap<>();
        List<TopoPos> path = new ArrayList<>(horizontalRun * 2 + 2);
        for (int i = 0; i <= horizontalRun; i++) {
            int y = start.y() - Math.min(i / 2, descent);
            path.add(new TopoPos(start.x() + i, y, start.z()));
        }
        TopoPos turn = path.get(path.size() - 1);
        if (turn.y() != entrance.pos().y()) {
            throw new IllegalArgumentException("entrance is too far above the arrival level to make a ramp");
        }
        for (int x = turningX - 1; x >= entrance.pos().x(); x--) {
            path.add(new TopoPos(x, entrance.pos().y(), entrance.pos().z()));
        }
        if (!path.get(path.size() - 1).equals(entrance.pos())) {
            throw new IllegalArgumentException("entrance node is not aligned with the arrival connector axis");
        }
        addTube(blocks, path);
        return finish("connector:arrival:" + entrance.id(), blocks, bounds, arrival, true);
    }

    /** True for the reserved 5x5 arrival pad and its two-block player clearance. */
    public static boolean isArrivalKeepOut(TopoPos pos, TopoPos arrival) {
        return Math.abs((long) pos.x() - arrival.x()) <= 2
                && Math.abs((long) pos.z() - arrival.z()) <= 2
                && pos.y() >= arrival.y() - 1 && pos.y() <= arrival.y() + 4;
    }

    private static void addTube(Map<TopoPos, RankedBlock> blocks, List<TopoPos> path) {
        if (path.size() > MAX_CENTERLINE_STEPS) {
            throw new IllegalArgumentException("segment centerline exceeds " + MAX_CENTERLINE_STEPS + " steps");
        }
        for (TopoPos center : path) {
            for (int dy = CORRIDOR_FLOOR; dy <= CORRIDOR_CEILING; dy++) {
                for (int dz = -CORRIDOR_HALF_WIDTH; dz <= CORRIDOR_HALF_WIDTH; dz++) {
                    for (int dx = -CORRIDOR_HALF_WIDTH; dx <= CORRIDOR_HALF_WIDTH; dx++) {
                        boolean interior = dy >= 0 && dy <= 2
                                && Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
                        put(blocks, center.offset(dx, dy, dz),
                                interior ? BlockKind.OPEN : BlockKind.ROCK,
                                interior ? 2 : 1);
                    }
                }
            }
        }
    }

    private static List<TopoPos> line(TopoPos from, TopoPos to) {
        long dx = (long) to.x() - from.x();
        long dy = (long) to.y() - from.y();
        long dz = (long) to.z() - from.z();
        long steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        if (steps > MAX_CENTERLINE_STEPS) {
            throw new IllegalArgumentException("edge centerline exceeds " + MAX_CENTERLINE_STEPS + " steps");
        }
        List<TopoPos> path = new ArrayList<>((int) steps + 1);
        if (steps == 0) {
            path.add(from);
            return path;
        }
        for (int i = 0; i <= steps; i++) {
            path.add(new TopoPos(
                    from.x() + (int) Math.round(dx * (double) i / steps),
                    from.y() + (int) Math.round(dy * (double) i / steps),
                    from.z() + (int) Math.round(dz * (double) i / steps)));
        }
        return path;
    }

    private static void addCheckpointPad(Map<TopoPos, RankedBlock> blocks, TopoPos feet) {
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                put(blocks, feet.offset(dx, -1, dz), BlockKind.ROCK, 6);
                for (int dy = 0; dy <= 2; dy++) {
                    put(blocks, feet.offset(dx, dy, dz), BlockKind.OPEN, 6);
                }
            }
        }
    }

    /** A single source block in a basalt-lined cup; the ring and floor keep
     * the liquid local without relying on the future M2c outer shell. */
    private static void addContainedLavaPocket(Map<TopoPos, RankedBlock> blocks, TopoPos center) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx != 0 || dz != 0) {
                    put(blocks, center.offset(dx, -2, dz), BlockKind.HAZARD_BOUNDARY, 6);
                }
                put(blocks, center.offset(dx, -3, dz), BlockKind.HAZARD_BOUNDARY, 6);
            }
        }
        put(blocks, center.offset(0, -2, 0), BlockKind.HAZARD_SOURCE, 7);
    }

    private static void addWalkableFloor(Map<TopoPos, RankedBlock> blocks,
                                         TopoPos feet, int radius) {
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dz * dz <= radius * radius) {
                    put(blocks, feet.offset(dx, -1, dz), BlockKind.ROCK, 3);
                }
            }
        }
    }

    private static void addLandmark(Map<TopoPos, RankedBlock> blocks, TopoPos center, int radius) {
        int offset = Math.max(2, radius - 2);
        for (int[] direction : new int[][] {{offset, 0}, {-offset, 0}, {0, offset}, {0, -offset}}) {
            int height = 2 + Math.floorMod(center.x() * 31 + center.z() * 17 + direction[0] * 7
                    + direction[1] * 13, 3);
            for (int dy = -1; dy < height; dy++) {
                put(blocks, center.offset(direction[0], dy, direction[1]), BlockKind.ROCK, 5);
            }
        }
    }

    private static double normalized(int offset, int radius) {
        return (double) offset * offset / ((double) radius * radius);
    }

    private static void put(Map<TopoPos, RankedBlock> blocks, TopoPos pos,
                            BlockKind kind, int priority) {
        RankedBlock existing = blocks.get(pos);
        if (existing == null || priority >= existing.priority()) {
            blocks.put(pos, new RankedBlock(kind, priority));
        }
    }

    private static SegmentPlan finish(String id, Map<TopoPos, RankedBlock> blocks,
                                      TopoBounds bounds, TopoPos arrival,
                                      boolean preserveArrivalKeepOut) {
        if (blocks.size() > MAX_OPERATIONS) {
            throw new IllegalArgumentException("segment " + id + " exceeds " + MAX_OPERATIONS + " operations");
        }
        List<TopoPos> positions = new ArrayList<>(blocks.keySet());
        positions.sort(POSITION_ORDER);
        List<BlockOperation> operations = new ArrayList<>(positions.size());
        for (TopoPos pos : positions) {
            if (!bounds.contains(pos)) {
                throw new IllegalArgumentException("segment " + id + " leaves bounds at " + pos);
            }
            if (isArrivalKeepOut(pos, arrival)) {
                if (preserveArrivalKeepOut) {
                    continue;
                }
                throw new IllegalArgumentException("segment " + id + " overlaps reserved arrival pad at " + pos);
            }
            operations.add(new BlockOperation(pos, blocks.get(pos).kind()));
        }
        operations.sort(Comparator.comparingInt(operation ->
                operation.kind() == BlockKind.HAZARD_SOURCE ? 1 : 0));
        return new SegmentPlan(id, operations);
    }
}
