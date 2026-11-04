package com.dwurdy.dawnmine.generation;

import com.dwurdy.dawnmine.topology.HubLayout;
import com.dwurdy.dawnmine.topology.NodeFeature;
import com.dwurdy.dawnmine.topology.TopoBounds;
import com.dwurdy.dawnmine.topology.TopoEdge;
import com.dwurdy.dawnmine.topology.TopoNode;
import com.dwurdy.dawnmine.topology.TopoPos;
import com.dwurdy.dawnmine.topology.TopologyPlan;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Final-merged-voxel traversal verification: replays the whole preparation
 * in real order (connector, nodes, edges, hub build, then every dressing
 * pass, then ores - which cannot change walkability) into one operation map
 * and proves a supported two-high walk exists from the hub landing to every
 * main-route node and checkpoint. Walkable = an open/rail cell with an open
 * cell above and a solid cell below; movement may step up or down at most
 * one block, matching the real game's movement.
 */
public final class MineTraversal {

    private MineTraversal() { }

    /** Replays preparation order into a single last-write-wins voxel map. */
    public static Map<TopoPos, SegmentGeometry.BlockKind> mergedWorld(TopologyPlan topology,
            TopoBounds bounds, TopoPos arrival) {
        Map<TopoPos, SegmentGeometry.BlockKind> world = new HashMap<>();
        List<String> ids = new ArrayList<>(PreparationPlan.structuralSegments(topology));
        Map<String, TopoEdge> edges = new HashMap<>();
        for (TopoEdge e : topology.edges()) {
            edges.put(SegmentGeometry.edgeSegmentId(e), e);
        }
        for (String id : ids) {
            apply(world, structural(topology, id, edges, bounds, arrival));
        }
        apply(world, SegmentGeometry.hub(topology, bounds, arrival));
        for (String id : ids) {
            apply(world, SegmentGeometry.dress(topology, id, bounds, arrival));
        }
        return world;
    }

    private static SegmentGeometry.SegmentPlan structural(TopologyPlan topology, String id,
            Map<String, TopoEdge> edges, TopoBounds bounds, TopoPos arrival) {
        if (id.startsWith("node:")) {
            return SegmentGeometry.node(topology, topology.node(id.substring(5)), bounds, arrival);
        }
        if (id.startsWith("edge:")) {
            return SegmentGeometry.edge(topology, edges.get(id), bounds, arrival);
        }
        return SegmentGeometry.entryConnector(topology, bounds, arrival);
    }

    private static void apply(Map<TopoPos, SegmentGeometry.BlockKind> world,
                              SegmentGeometry.SegmentPlan plan) {
        for (SegmentGeometry.BlockOperation op : plan.operations()) {
            world.put(op.position(), op.kind());
        }
    }

    private static boolean walkable(Map<TopoPos, SegmentGeometry.BlockKind> world, TopoPos pos) {
        SegmentGeometry.BlockKind kind = world.get(pos);
        if (kind != SegmentGeometry.BlockKind.OPEN && kind != SegmentGeometry.BlockKind.RAIL) {
            return false;
        }
        SegmentGeometry.BlockKind head = world.get(pos.offset(0, 1, 0));
        if (head != SegmentGeometry.BlockKind.OPEN && head != SegmentGeometry.BlockKind.RAIL) {
            return false;
        }
        // A cell with no operation is untouched natural rock - solid ground.
        SegmentGeometry.BlockKind floor = world.get(pos.offset(0, -1, 0));
        return floor == null || switch (floor) {
            case ROCK, ORE, TIMBER, PLANKS, HAZARD_BOUNDARY, MARKER -> true;
            default -> false;
        };
    }

    /** Positions reachable from the landing under 2-high, step-1 movement. */
    public static Set<TopoPos> reachableFrom(TopoPos start,
            Map<TopoPos, SegmentGeometry.BlockKind> world, int maxCells) {
        Set<TopoPos> visited = new HashSet<>();
        ArrayDeque<TopoPos> queue = new ArrayDeque<>();
        if (!walkable(world, start)) {
            return visited;
        }
        visited.add(start);
        queue.add(start);
        while (!queue.isEmpty() && visited.size() < maxCells) {
            TopoPos cur = queue.poll();
            for (int[] d : new int[][] {
                    {1,0,0},{-1,0,0},{0,0,1},{0,0,-1},
                    {1,0,1},{1,0,-1},{-1,0,1},{-1,0,-1}}) {
                for (int dy = -1; dy <= 1; dy++) {
                    TopoPos next = cur.offset(d[0], dy, d[2]);
                    if (visited.contains(next) || !walkable(world, next)) {
                        continue;
                    }
                    int crossingY = Math.max(cur.y(), next.y());
                    if (dy > 0 && !bodyAir(world, cur.offset(0, 2, 0))) {
                        continue;
                    }
                    if (d[0] != 0 && d[2] != 0
                            && (!clearColumn(world,
                                    new TopoPos(cur.x() + d[0], crossingY, cur.z()))
                                || !clearColumn(world,
                                    new TopoPos(cur.x(), crossingY, cur.z() + d[2])))) {
                        continue;
                    }
                    visited.add(next);
                    queue.add(next);
                }
            }
        }
        return visited;
    }

    private static boolean clearColumn(Map<TopoPos, SegmentGeometry.BlockKind> world, TopoPos feet) {
        return bodyAir(world, feet) && bodyAir(world, feet.offset(0, 1, 0));
    }

    private static boolean bodyAir(Map<TopoPos, SegmentGeometry.BlockKind> world, TopoPos pos) {
        // Vanilla rails have no collision shape; they are not solid corners.
        return world.get(pos) == SegmentGeometry.BlockKind.OPEN
                || world.get(pos) == SegmentGeometry.BlockKind.RAIL;
    }

    /** Main-route and checkpoint positions a prepared run must reach. */
    public static List<TopoPos> requiredTargets(TopologyPlan topology) {
        List<TopoPos> targets = new ArrayList<>();
        PassageNetwork network = PassageNetwork.of(topology, HubLayout.LANDING);
        for (String id : topology.mainPath()) {
            TopoNode node = topology.node(id);
            if (node != null) {
                targets.add(network.center(node.id()));
            }
        }
        for (TopoNode node : topology.checkpoints()) {
            targets.add(network.center(node.id()));
        }
        for (TopoNode node : topology.nodes()) {
            if (node.has(NodeFeature.CHECKPOINT)) {
                targets.add(network.center(node.id()));
            }
        }
        targets.add(HubLayout.TELEPORTER_CONSOLE.offset(0, 0, 1)); // console floor
        targets.add(HubLayout.DISPATCH_CONSOLE.offset(1, 0, 0));   // console floor
        return targets;
    }
}
