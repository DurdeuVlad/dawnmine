package com.dwurdy.dawnmine.generation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.dawnmine.topology.HubLayout;
import com.dwurdy.dawnmine.topology.TopoBounds;
import com.dwurdy.dawnmine.topology.TopoPos;
import com.dwurdy.dawnmine.topology.TopologyPlan;
import com.dwurdy.dawnmine.topology.TopologyPlanner;
import com.dwurdy.dawnmine.topology.TopologyProfile;
import java.util.List;
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

    /** End to end: the overlapping-lobe carve technique inherently leaves
     * some standable pockets unreachable (confirmed empirically: ~9,000+
     * per seed on the raw carve, out of ~135,000-140,000 total reachable
     * cells - this is not a rare edge case, it is a property of carving
     * rooms as unions of overlapping implicit shapes). {@link
     * UnreachablePocketSealer}, run exactly as {@link
     * com.dwurdy.dawnmine.generation.MineGenerator} runs it in production
     * (node boxes, edge-centerline windows, and the hub's own fixed
     * footprint, in up to 25 passes), closes ALL of it - zero residual
     * standable-but-unreachable cells, confirmed across five fuzzed seeds -
     * without ever costing a single required target.
     *
     * <p>Getting to zero took three real bugs caught and fixed, in order:
     * sealing without first freezing one consistent reachable snapshot
     * cascaded into destroying the mine (every node's BFS re-run against a
     * world an earlier node had already mutated); sealing without a
     * standability filter on the candidate cell removed harmless open-air
     * headroom, not just floor-level traps (700k+ cells sealed, reachable
     * area collapsed from ~135k to ~1k); and scanning only topology NODES
     * converged (stopped finding anything new to seal) while still leaving
     * hundreds of real trapped cells per seed sitting inside edge passage
     * corridors and the hub - neither is a topology node, so neither was
     * ever scanned. All three are asserted here so none can silently
     * reappear. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {1, 7, 42, 99, 256})
    void noStandableCellIsATrapOutsideTheRequiredRoute(long seed) {
        TopologyPlan plan = TopologyPlanner.plan(seed, TopologyProfile.builtin(), BOUNDS);
        Map<TopoPos, SegmentGeometry.BlockKind> world = MineTraversal.mergedWorld(plan, BOUNDS, ARRIVAL);
        int trappedBefore = countStandableTrapped(world, MineTraversal.reachableFrom(ARRIVAL, world, 5_000_000));
        assertTrue(trappedBefore > 500, "seed " + seed
                + " raw carve has suspiciously few trapped cells (" + trappedBefore
                + ") - the fixture this test's bound was calibrated against may have changed");

        PassageNetwork network = PassageNetwork.of(plan, ARRIVAL);
        Set<TopoPos> reachable = MineTraversal.reachableFrom(ARRIVAL, world, 5_000_000);
        for (int pass = 0; pass < 25; pass++) {
            Set<TopoPos> toSeal = new java.util.HashSet<>();
            for (var node : plan.nodes()) {
                TopoPos center = network.center(node.id());
                if (center == null) center = node.pos();
                int hw = SegmentGeometry.roomCarveHalfWidth(node.kind());
                int hh = SegmentGeometry.roomCarveHalfHeight(node.kind());
                toSeal.addAll(UnreachablePocketSealer.findUnreachableIn(world, reachable,
                        center.offset(-hw, -hh, -hw), center.offset(hw, hh, hw)));
            }
            for (var edge : plan.edges()) {
                List<TopoPos> path = SegmentGeometry.passageCenterline(plan, edge);
                for (TopoPos[] window : SegmentGeometry.edgeSealWindows(path)) {
                    toSeal.addAll(UnreachablePocketSealer.findUnreachableIn(world, reachable,
                            window[0], window[1]));
                }
            }
            toSeal.addAll(UnreachablePocketSealer.findUnreachableIn(world, reachable,
                    HubLayout.SAFE_MIN.offset(-10, -2, -10), HubLayout.SAFE_MAX.offset(10, 2, 10)));
            if (toSeal.isEmpty()) break;
            for (TopoPos pos : toSeal) {
                world.put(pos, SegmentGeometry.BlockKind.WALL_SKIN);
            }
            reachable = MineTraversal.reachableFrom(ARRIVAL, world, 5_000_000);
        }

        StringBuilder missing = new StringBuilder();
        for (TopoPos target : MineTraversal.requiredTargets(plan, ARRIVAL)) {
            if (!reachable.contains(target)) missing.append(' ').append(target);
        }
        assertTrue(missing.isEmpty(), "seed " + seed
                + " sealing cost a required target:" + missing);

        int trappedAfter = countStandableTrapped(world, reachable);
        assertTrue(trappedAfter == 0, "seed " + seed
                + " left standable traps outside the required route: "
                + trappedBefore + " -> " + trappedAfter);
    }

    private static int countStandableTrapped(Map<TopoPos, SegmentGeometry.BlockKind> world,
            Set<TopoPos> reachable) {
        int count = 0;
        for (Map.Entry<TopoPos, SegmentGeometry.BlockKind> entry : world.entrySet()) {
            if (entry.getValue() != SegmentGeometry.BlockKind.OPEN
                    && entry.getValue() != SegmentGeometry.BlockKind.RAIL) continue;
            TopoPos pos = entry.getKey();
            if (reachable.contains(pos)) continue;
            if (MineTraversal.reachableFrom(pos, world, 1).contains(pos)) count++;
        }
        return count;
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
