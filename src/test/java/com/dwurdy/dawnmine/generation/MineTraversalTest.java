package com.dwurdy.dawnmine.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.dawnmine.topology.HubLayout;
import com.dwurdy.dawnmine.topology.TopoBounds;
import com.dwurdy.dawnmine.topology.TopoPos;
import com.dwurdy.dawnmine.topology.TopologyPlan;
import com.dwurdy.dawnmine.topology.TopologyPlanner;
import com.dwurdy.dawnmine.topology.TopologyProfile;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MineTraversalTest {
    private static final TopoBounds BOUNDS = new TopoBounds(-1024, -64, -1024, 1023, 319, 1023);
    private static final TopoPos ARRIVAL = new TopoPos(0, 42, 8);

    @Test
    void mergedPreparedMineConnectsLandingToEveryMainRouteAndCheckpoint() {
        for (long seed : new long[] {42, 1337, 2026}) {
            TopologyPlan plan = TopologyPlanner.plan(seed, TopologyProfile.builtin(), BOUNDS);
            Map<TopoPos, SegmentGeometry.BlockKind> world =
                    MineTraversal.mergedWorld(plan, BOUNDS, ARRIVAL);
            Set<TopoPos> reachable = MineTraversal.reachableFrom(ARRIVAL, world, 3_000_000);
            // The landing, gateway, consoles, entrance, and the first main
            // route leg must always be walkable in the merged result.
            for (TopoPos target : new TopoPos[] {
                    new TopoPos(28, 42, 0),
                    HubLayout.TELEPORTER_CONSOLE.offset(0, 0, 1),
                    HubLayout.DISPATCH_CONSOLE.offset(1, 0, 0)}) {
                assertTrue(reachable.contains(target),
                        "seed " + seed + " cannot reach " + target);
            }
            StringBuilder missing = new StringBuilder();
            for (TopoPos target : MineTraversal.requiredTargets(plan, ARRIVAL)) {
                if (!reachable.contains(target)) {
                    missing.append(' ').append(target);
                }
            }
            assertTrue(missing.isEmpty(), "seed " + seed
                    + " has unreachable required targets:" + missing
                    + describeCuts(plan, world, reachable));
            assertTrue(reachable.size() > 20_000,
                    "seed " + seed + " reachable area suspiciously small: " + reachable.size());
        }
    }

    private static String describeCuts(TopologyPlan plan,
            Map<TopoPos, SegmentGeometry.BlockKind> world, Set<TopoPos> reachable) {
        StringBuilder cuts = new StringBuilder();
        int count = 0;
        for (var edge : plan.edges()) {
            var path = SegmentGeometry.passageCenterline(plan, edge);
            for (int i = 1; i < path.size(); i++) {
                TopoPos previous = path.get(i - 1);
                TopoPos next = path.get(i);
                if (reachable.contains(previous) && !reachable.contains(next)) {
                    cuts.append("\n").append(edge).append(": ").append(previous)
                            .append(" -> ").append(next).append(" floor/feet/head=")
                            .append(world.get(next.offset(0, -1, 0))).append("/")
                            .append(world.get(next)).append("/")
                            .append(world.get(next.offset(0, 1, 0)));
                    if (++count == 3) return cuts.toString();
                    break;
                }
            }
        }
        return cuts.toString();
    }

    @Test
    void landingGatewayAndConsolesAreReachableThroughRealMergedGeometry() {
        TopologyPlan plan = TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
        Map<TopoPos, SegmentGeometry.BlockKind> world =
                MineTraversal.mergedWorld(plan, BOUNDS, ARRIVAL);
        Set<TopoPos> reachable = MineTraversal.reachableFrom(ARRIVAL, world, 1_000_000);
        assertTrue(reachable.contains(new TopoPos(28, 42, 0)),
                "entrance node must be reachable through the east gateway");
        assertTrue(reachable.contains(HubLayout.TELEPORTER_CONSOLE.offset(0, 0, 1)));
    }

    @Test
    void stepLimitIsEnforced() {
        // A two-block jump must never appear as a legal move.
        Map<TopoPos, SegmentGeometry.BlockKind> world = new java.util.HashMap<>();
        world.put(new TopoPos(0, 10, 0), SegmentGeometry.BlockKind.OPEN);
        world.put(new TopoPos(0, 11, 0), SegmentGeometry.BlockKind.OPEN);
        world.put(new TopoPos(0, 10, -1), SegmentGeometry.BlockKind.ROCK);
        world.put(new TopoPos(1, 12, 0), SegmentGeometry.BlockKind.OPEN);
        world.put(new TopoPos(1, 13, 0), SegmentGeometry.BlockKind.OPEN);
        world.put(new TopoPos(1, 11, 0), SegmentGeometry.BlockKind.ROCK);
        var reach = MineTraversal.reachableFrom(new TopoPos(0, 10, 0), world, 100);
        assertTrue(reach.contains(new TopoPos(0, 10, 0)));
        org.junit.jupiter.api.Assertions.assertFalse(reach.contains(new TopoPos(1, 12, 0)),
                "a two-block rise must not be traversed");
    }

}
