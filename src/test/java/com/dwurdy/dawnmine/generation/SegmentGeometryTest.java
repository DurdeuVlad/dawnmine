package com.dwurdy.dawnmine.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.dawnmine.topology.EdgeKind;
import com.dwurdy.dawnmine.topology.NodeFeature;
import com.dwurdy.dawnmine.topology.NodeKind;
import com.dwurdy.dawnmine.topology.TopoBounds;
import com.dwurdy.dawnmine.topology.TopoEdge;
import com.dwurdy.dawnmine.topology.TopoNode;
import com.dwurdy.dawnmine.topology.TopoPos;
import com.dwurdy.dawnmine.topology.TopologyPlan;
import com.dwurdy.dawnmine.topology.TopologyPlanner;
import com.dwurdy.dawnmine.topology.TopologyProfile;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class SegmentGeometryTest {
    private static final TopoBounds BOUNDS = new TopoBounds(-1024, -64, -1024, 1023, 319, 1023);
    private static final TopoBounds TIGHT_BOUNDS = new TopoBounds(-12, 0, -12, 12, 24, 12);
    private static final TopoPos ARRIVAL = new TopoPos(0, 42, 8);

    private static TopologyPlan plan() {
        return TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
    }

    private static TopologyPlan plan(long seed) {
        return TopologyPlanner.plan(seed, TopologyProfile.builtin(), BOUNDS);
    }

    @Test
    void nodeAndEdgePlansAreDeterministicAndHaveStableIds() {
        TopologyPlan plan = plan();
        TopoNode node = plan.node("m000");

        SegmentGeometry.SegmentPlan firstNode = SegmentGeometry.node(plan, node, BOUNDS, ARRIVAL);
        SegmentGeometry.SegmentPlan secondNode = SegmentGeometry.node(plan, node, BOUNDS, ARRIVAL);
        assertEquals("node:m000", firstNode.id());
        assertEquals(firstNode.operations(), secondNode.operations());

        var edge = plan.edges().get(0);
        SegmentGeometry.SegmentPlan firstEdge = SegmentGeometry.edge(plan, edge, BOUNDS, ARRIVAL);
        SegmentGeometry.SegmentPlan secondEdge = SegmentGeometry.edge(plan, edge, BOUNDS, ARRIVAL);
        assertEquals("edge:" + edge.a() + ":" + edge.b(), firstEdge.id());
        assertEquals(firstEdge.operations(), secondEdge.operations());
    }

    @Test
    void differentSeedsProduceDifferentGeometry() {
        TopoEdge edgeA = plan(42).edges().get(0);
        TopoNode nodeA = plan(42).node("m000");
        TopoEdge edgeB = plan(1337).edges().get(0);
        TopoNode nodeB = plan(1337).node("m000");
        assertNotEquals(
                SegmentGeometry.edge(plan(42), edgeA, BOUNDS, ARRIVAL).operations(),
                SegmentGeometry.edge(plan(1337), edgeB, BOUNDS, ARRIVAL).operations());
        assertNotEquals(
                SegmentGeometry.node(plan(42), nodeA, BOUNDS, ARRIVAL).operations(),
                SegmentGeometry.node(plan(1337), nodeB, BOUNDS, ARRIVAL).operations());
    }

    @Test
    void windingPathIsNotCollinear() {
        TopologyPlan plan = plan();
        int checked = 0;
        for (TopoEdge edge : plan.edges()) {
            List<TopoPos> path = SegmentGeometry.passageCenterline(plan, edge);
            if (path.size() < 12) {
                continue;
            }
            checked++;
            TopoPos a = plan.node(edge.a()).pos();
            TopoPos b = plan.node(edge.b()).pos();
            double dx = b.x() - a.x();
            double dz = b.z() - a.z();
            double len = Math.hypot(dx, dz);
            if (len < 8) {
                continue;
            }
            double maxDeviation = 0;
            for (TopoPos cell : path.subList(2, path.size() - 2)) {
                double cross = Math.abs(dx * (cell.z() - a.z()) - dz * (cell.x() - a.x())) / len;
                maxDeviation = Math.max(maxDeviation, cross);
            }
            assertTrue(maxDeviation > 0.51,
                    "edge " + edge + " centerline is suspiciously straight (max deviation "
                            + maxDeviation + ")");
        }
        assertTrue(checked > 0, "test exercised no real edge");
    }

    @Test
    void centerlineIsSupportedAndTwoHighWalkable() {
        TopologyPlan plan = plan();
        int checked = 0;
        for (TopoEdge edge : plan.edges()) {
            List<TopoPos> path = SegmentGeometry.passageCenterline(plan, edge);
            if (path.size() < 4) {
                continue;
            }
            checked++;
            var segment = SegmentGeometry.edge(plan, edge, BOUNDS, ARRIVAL);
            Map<TopoPos, SegmentGeometry.BlockKind> blocks = asMap(segment);
            for (int i = 0; i + 1 < path.size(); i++) {
                TopoPos a = path.get(i);
                TopoPos b = path.get(i + 1);
                assertTrue(Math.abs(a.y() - b.y()) <= 1,
                        "vertical step >1 on " + edge + " at index " + i);
                assertTrue(Math.abs(a.x() - b.x()) <= 1 && Math.abs(a.z() - b.z()) <= 1,
                        "horizontal gap on " + edge + " at index " + i);
            }
            for (TopoPos cell : path) {
                // Cells clipped by the hub reserve are sealed wall by design.
                if (SegmentGeometry.isArrivalKeepOut(cell, ARRIVAL)
                        || SegmentGeometry.isArrivalKeepOut(cell.offset(0, 1, 0), ARRIVAL)) {
                    continue;
                }
                assertEquals(SegmentGeometry.BlockKind.OPEN, blocks.get(cell),
                        edge + " centerline cell not open at " + cell);
                assertEquals(SegmentGeometry.BlockKind.OPEN, blocks.get(cell.offset(0, 1, 0)),
                        edge + " lacks headroom at " + cell);
            }
            // The dressing pass guarantees a solid floor under every step.
            var dress = SegmentGeometry.dress(plan, "edge:" + edge.a() + ":" + edge.b(),
                    BOUNDS, ARRIVAL);
            Map<TopoPos, SegmentGeometry.BlockKind> dressing = asMap(dress);
            java.util.Set<TopoPos> clearance = SegmentGeometry.wholeRunClearance(plan, ARRIVAL);
            java.util.Set<TopoPos> headCells = SegmentGeometry.wholeRunHeadCells(plan, ARRIVAL);
            for (TopoPos cell : path) {
                TopoPos floorCell = cell.offset(0, -1, 0);
                // Cells the hub reserve seals are wall by design.
                if (SegmentGeometry.isArrivalKeepOut(cell, ARRIVAL)
                        || SegmentGeometry.isArrivalKeepOut(floorCell, ARRIVAL)) {
                    continue;
                }
                SegmentGeometry.BlockKind floor = dressing.get(floorCell);
                // A floor cell that is itself another route's walk space is a
                // shared open junction: that route provides the support.
                assertTrue(floor == SegmentGeometry.BlockKind.ROCK
                        || floor == SegmentGeometry.BlockKind.PLANKS
                        || clearance.contains(floorCell)
                        || headCells.contains(floorCell),
                        edge + " lacks supported floor at " + cell);
            }
        }
        assertTrue(checked > 0, "test exercised no real edge");
    }

    @Test
    void cavernsAreWideAndTall() {
        TopologyPlan plan = plan();
        TopoNode cavern = plan.nodes().stream()
                .filter(node -> node.kind() == NodeKind.CAVERN).findFirst().orElseThrow();
        var blocks = asMap(SegmentGeometry.node(plan, cavern, BOUNDS, ARRIVAL));
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Map.Entry<TopoPos, SegmentGeometry.BlockKind> e : blocks.entrySet()) {
            if (e.getValue() != SegmentGeometry.BlockKind.OPEN) {
                continue;
            }
            TopoPos p = e.getKey();
            minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
            minY = Math.min(minY, p.y()); maxY = Math.max(maxY, p.y());
            minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
        }
        assertTrue(maxX - minX + 1 >= 36 || maxZ - minZ + 1 >= 36,
                "cavern horizontal span too small: " + (maxX - minX + 1) + "x" + (maxZ - minZ + 1));
        assertTrue(maxY - minY + 1 >= 16,
                "cavern vertical open span too small: " + (maxY - minY + 1));
    }

    @Test
    void dressPassAddsTimberRailsAndLightsWithClearance() {
        TopologyPlan plan = plan();
        boolean sawTimber = false;
        boolean sawRail = false;
        boolean sawLight = false;
        for (TopoEdge edge : plan.edges()) {
            var dress = SegmentGeometry.dress(plan, "edge:" + edge.a() + ":" + edge.b(),
                    BOUNDS, ARRIVAL);
            for (var op : dress.operations()) {
                sawTimber |= op.kind() == SegmentGeometry.BlockKind.TIMBER;
                sawRail |= op.kind() == SegmentGeometry.BlockKind.RAIL;
                sawLight |= op.kind() == SegmentGeometry.BlockKind.LIGHT;
            }
        }
        assertTrue(sawTimber, "no timber framing anywhere in the run");
        assertTrue(sawRail, "no rails anywhere in the run");
        assertTrue(sawLight, "no lamps anywhere in the run");
    }

    @Test
    void decorationsNeverBlockTheCenterline() {
        TopologyPlan plan = plan();
        for (TopoEdge edge : plan.edges().subList(0, Math.min(8, plan.edges().size()))) {
            List<TopoPos> path = SegmentGeometry.passageCenterline(plan, edge);
            var dress = SegmentGeometry.dress(plan, "edge:" + edge.a() + ":" + edge.b(),
                    BOUNDS, ARRIVAL);
            Set<TopoPos> decorated = new HashSet<>();
            for (var op : dress.operations()) {
                if (op.kind() == SegmentGeometry.BlockKind.TIMBER
                        || op.kind() == SegmentGeometry.BlockKind.FENCE
                        || op.kind() == SegmentGeometry.BlockKind.LIGHT) {
                    decorated.add(op.position());
                }
            }
            for (TopoPos cell : path) {
                assertFalse(decorated.contains(cell) || decorated.contains(cell.offset(0, 1, 0)),
                        "decoration blocks the 2-high walk column at " + cell);
            }
        }
    }

    @Test
    void nodeAndEdgeOperationsStayInsideBoundsAndOutsideArrivalKeepOut() {
        TopologyPlan plan = plan();
        var segments = new SegmentGeometry.SegmentPlan[] {
                SegmentGeometry.node(plan, plan.node("m000"), BOUNDS, ARRIVAL),
                SegmentGeometry.edge(plan, plan.edges().get(0), BOUNDS, ARRIVAL),
                SegmentGeometry.dress(plan, "edge:" + plan.edges().get(0).a()
                        + ":" + plan.edges().get(0).b(), BOUNDS, ARRIVAL)
        };

        for (var segment : segments) {
            for (var operation : segment.operations()) {
                assertTrue(BOUNDS.contains(operation.position()),
                        segment.id() + " wrote out of bounds: " + operation.position());
                assertFalse(SegmentGeometry.isArrivalKeepOut(operation.position(), ARRIVAL),
                        segment.id() + " overlapped the arrival pad: " + operation.position());
            }
        }
    }

    @Test
    void checkpointHasSupportedFloorAndThreeBlockClearance() {
        TopologyPlan plan = plan();
        TopoNode base = plan.checkpoints().get(0);
        TopoNode checkpoint = new TopoNode(base.id(), base.kind(), base.region(), base.pos(),
                Set.of(NodeFeature.CHECKPOINT, NodeFeature.LANDMARK), base.label());
        TopologyPlan landmarkCheckpointPlan = new TopologyPlan(plan.seed(), plan.profileId(),
                plan.bounds(), plan.nodes().stream()
                        .map(node -> node.id().equals(checkpoint.id()) ? checkpoint : node).toList(),
                plan.edges(), plan.mainPath(), plan.entranceId(), plan.terminusId());
        var segment = SegmentGeometry.node(landmarkCheckpointPlan, checkpoint, BOUNDS, ARRIVAL);
        Map<TopoPos, SegmentGeometry.BlockKind> blocks = asMap(segment);
        TopoPos feet = PassageNetwork.of(landmarkCheckpointPlan, ARRIVAL).center(checkpoint.id());

        assertEquals(SegmentGeometry.BlockKind.ROCK, blocks.get(feet.offset(0, -1, 0)));
        assertEquals(SegmentGeometry.BlockKind.OPEN, blocks.get(feet));
        assertEquals(SegmentGeometry.BlockKind.OPEN, blocks.get(feet.offset(0, 1, 0)));
        assertEquals(SegmentGeometry.BlockKind.OPEN, blocks.get(feet.offset(0, 2, 0)));
    }

    @Test
    void ordinaryNodeRoomsAlsoHaveAStableWalkableFloor() {
        TopologyPlan plan = plan();
        for (TopoNode node : plan.nodes().stream()
                .filter(candidate -> candidate.kind() == NodeKind.ROUTE
                        || candidate.kind() == NodeKind.CAVERN)
                .limit(5).toList()) {
            Map<TopoPos, SegmentGeometry.BlockKind> blocks = asMap(
                    SegmentGeometry.node(plan, node, BOUNDS, ARRIVAL));
            TopoPos center = PassageNetwork.of(plan, ARRIVAL).center(node.id());
            assertEquals(SegmentGeometry.BlockKind.ROCK, blocks.get(center.offset(0, -1, 0)),
                    "node lacks floor support: " + node.id());
            assertEquals(SegmentGeometry.BlockKind.OPEN, blocks.get(center),
                    "node center is blocked: " + node.id());
            assertEquals(SegmentGeometry.BlockKind.OPEN, blocks.get(center.offset(0, 1, 0)),
                    "node lacks head clearance: " + node.id());
        }
    }

    @Test
    void everyEdgeOpensItsPlannedEndpoints() {
        TopologyPlan plan = plan();
        var edge = plan.edges().stream()
                .filter(candidate -> plan.node(candidate.a()).kind() != NodeKind.ENTRANCE)
                .findFirst().orElseThrow();
        var segment = SegmentGeometry.edge(plan, edge, BOUNDS, ARRIVAL);
        Map<TopoPos, SegmentGeometry.BlockKind> blocks = asMap(segment);

        assertEquals(SegmentGeometry.BlockKind.OPEN,
                blocks.get(PassageNetwork.of(plan, ARRIVAL).center(edge.a())));
        assertEquals(SegmentGeometry.BlockKind.OPEN,
                blocks.get(PassageNetwork.of(plan, ARRIVAL).center(edge.b())));
    }

    @Test
    void chaosCavernLavaHasABasaltRingAndFloor() {
        TopologyPlan plan = plan();
        TopoNode base = plan.nodes().stream().filter(node -> node.kind() == NodeKind.CAVERN)
                .findFirst().orElseThrow();
        TopoNode cavern = new TopoNode(base.id(), NodeKind.CAVERN, "chaos", base.pos(),
                Set.of(), base.label());
        TopologyPlan chaosPlan = new TopologyPlan(plan.seed(), plan.profileId(), plan.bounds(),
                plan.nodes().stream().map(node -> node.id().equals(cavern.id()) ? cavern : node).toList(),
                plan.edges(), plan.mainPath(), plan.entranceId(), plan.terminusId());
        var blocks = asMap(SegmentGeometry.node(chaosPlan, cavern, BOUNDS, ARRIVAL));
        TopoPos source = PassageNetwork.of(chaosPlan, ARRIVAL).center(cavern.id()).offset(0, -2, 0);

        assertEquals(SegmentGeometry.BlockKind.HAZARD_SOURCE, blocks.get(source));
        assertEquals(SegmentGeometry.BlockKind.HAZARD_BOUNDARY, blocks.get(source.offset(1, 0, 0)));
        assertEquals(SegmentGeometry.BlockKind.HAZARD_BOUNDARY, blocks.get(source.offset(-1, 0, 0)));
        assertEquals(SegmentGeometry.BlockKind.HAZARD_BOUNDARY, blocks.get(source.offset(0, 0, 1)));
        assertEquals(SegmentGeometry.BlockKind.HAZARD_BOUNDARY, blocks.get(source.offset(0, 0, -1)));
        assertEquals(SegmentGeometry.BlockKind.HAZARD_BOUNDARY, blocks.get(source.offset(0, -1, 0)));
    }

    @Test
    void gatewayConnectorWalksFromHubWallToEntrance() {
        TopologyPlan plan = plan();
        var connector = SegmentGeometry.entryConnector(plan, BOUNDS, ARRIVAL);
        Map<TopoPos, SegmentGeometry.BlockKind> blocks = asMap(connector);

        assertEquals("connector:arrival:m000", connector.id());
        assertEquals(SegmentGeometry.BlockKind.OPEN,
                blocks.get(new TopoPos(28, 42, 0)), "connector must reach the entrance chamber");
        assertTrue(connector.operations().stream().noneMatch(operation ->
                SegmentGeometry.isArrivalKeepOut(operation.position(), ARRIVAL)),
                "connector wrote into the protected hub interior");
        // The gateway passage is a short horizontal supported walk.
        List<TopoPos> path = SegmentGeometry.connectorCenterline(plan, ARRIVAL);
        assertEquals(new TopoPos(28, 42, 0), path.get(path.size() - 1));
        for (int i = 0; i + 1 < path.size(); i++) {
            assertTrue(Math.abs(path.get(i).y() - path.get(i + 1).y()) <= 1);
        }
    }

    @Test
    void foreignCrossingOfGatewayBandMergesWithoutLoweringAnchors() {
        // Regression: seed 3874576496800286393 routes edge:m000:m001 across
        // the gateway band at (27,43,0). Resolution must converge and keep
        // every connector sample at the fixed hub floor instead of failing
        // the run as "crossing merge would lower the fixed hub anchor".
        TopologyPlan plan = plan(3874576496800286393L);
        PassageNetwork network = PassageNetwork.of(plan, ARRIVAL);
        List<TopoPos> connector = network.path("connector:arrival:" + plan.entranceId());
        assertFalse(connector.isEmpty(), "connector must resolve for the regression seed");
        for (TopoPos sample : connector) {
            assertEquals(42, sample.y(), "gateway anchor was moved: " + sample);
        }
        // The merged world must still connect the landing to the entrance.
        Map<TopoPos, SegmentGeometry.BlockKind> world =
                MineTraversal.mergedWorld(plan, BOUNDS, ARRIVAL);
        assertTrue(MineTraversal.reachableFrom(ARRIVAL, world, 1_000_000)
                .contains(new TopoPos(28, 42, 0)),
                "entrance must stay reachable through the merged gateway");
    }

    @Test
    void foreignCrossingBelowGatewaySkirtsApronWithoutLivelock() {
        // Regression: a foreign route aimed under the gateway band must
        // never keep an interior sample on the connector's own column row
        // (z == 0 in the band) - that is the one place a foreign path can
        // cluster against the fixed anchor and oscillate to MAX_PASSES or
        // trench the corridor floor. Route n001->n002 at y=40 is pointed
        // straight through the band; several seeds exercise the wobble.
        for (long planSeed = 11; planSeed <= 30; planSeed++) {
            TopologyPlan plan = belowGatewayPlan(planSeed);
            PassageNetwork network = PassageNetwork.of(plan, ARRIVAL);
            List<TopoPos> foreign = network.path("edge:n001:n002");
            for (int i = 1; i + 1 < foreign.size(); i++) {
                TopoPos sample = foreign.get(i);
                boolean onRow = sample.z() == 0 && sample.x() >= 22
                        && sample.x() <= 28 && sample.y() >= 33 && sample.y() <= 51;
                assertFalse(onRow,
                        "foreign sample on the connector column row " + sample
                                + " (seed " + planSeed + ")");
            }
            List<TopoPos> connector = network.path("connector:arrival:m000");
            for (TopoPos sample : connector) {
                assertEquals(42, sample.y(), "connector anchor moved: " + sample);
            }
            for (TopoPos sample : network.path("edge:m000:n001")) {
                if (sample.z() == 0 && sample.x() >= 22 && sample.x() <= 28) {
                    assertEquals(42, sample.y(),
                            "entrance-edge band sample left off the floor " + sample);
                }
            }
        }
    }

    private static TopologyPlan belowGatewayPlan(long seed) {
        TopoNode entrance = new TopoNode("m000", NodeKind.ENTRANCE, "upper",
                new TopoPos(28, 42, 0), Set.of(), "entrance");
        TopoNode north = new TopoNode("n001", NodeKind.ROUTE, "upper",
                new TopoPos(25, 40, -12), Set.of(), "north");
        TopoNode south = new TopoNode("n002", NodeKind.ROUTE, "upper",
                new TopoPos(25, 40, 12), Set.of(), "south");
        return new TopologyPlan(seed, "standard", BOUNDS,
                List.of(entrance, north, south),
                List.of(new TopoEdge("m000", "n001", EdgeKind.MAIN),
                        new TopoEdge("n001", "n002", EdgeKind.BRANCH)),
                List.of("m000", "n001", "n002"), "m000", "n002");
    }

    @Test
    void hubSegmentBuildsShellConsolesAndKeepsLanesClear() {
        TopologyPlan plan = plan();
        var hub = SegmentGeometry.hub(plan, BOUNDS, ARRIVAL);
        Map<TopoPos, SegmentGeometry.BlockKind> blocks = new java.util.TreeMap<>(
                java.util.Comparator.comparingInt(TopoPos::y)
                        .thenComparingInt(TopoPos::z).thenComparingInt(TopoPos::x));
        for (var op : hub.operations()) {
            blocks.put(op.position(), op.kind());
        }
        // Landing is open with a solid floor slab beneath.
        assertEquals(SegmentGeometry.BlockKind.OPEN, blocks.get(new TopoPos(0, 42, 8)));
        assertEquals(SegmentGeometry.BlockKind.OPEN, blocks.get(new TopoPos(0, 43, 8)));
        // Consoles exist.
        assertTrue(hub.operations().stream().anyMatch(op ->
                op.position().equals(new TopoPos(0, 42, -14))
                        && "minecraft:lodestone".equals(op.blockId())));
        assertTrue(hub.operations().stream().anyMatch(op ->
                op.position().equals(new TopoPos(-12, 42, 0))
                        && "minecraft:barrel".equals(op.blockId())));
        assertTrue(hub.operations().stream().anyMatch(op ->
                "minecraft:oak_sign".equals(op.blockId())));
        // Roof caps below the stone baseline (y<=59).
        assertTrue(hub.operations().stream().noneMatch(op -> op.position().y() > 59));
        assertTrue(hub.operations().stream().anyMatch(op ->
                op.position().y() == 58 && op.kind() == SegmentGeometry.BlockKind.MARKER));
        // East wall has the gateway opening (no ops blocking x=22,z0 band y42-45).
        assertFalse(blocks.containsKey(new TopoPos(22, 42, 0))
                        && blocks.get(new TopoPos(22, 42, 0)) != SegmentGeometry.BlockKind.OPEN,
                "gateway opening must not be filled");
    }

    @Test
    void cursorSlicesReplayTheSameOperations() {
        TopologyPlan plan = plan();
        var segment = SegmentGeometry.edge(plan, plan.edges().get(0), BOUNDS, ARRIVAL);
        int cursor = Math.min(7, segment.operations().size());
        int end = Math.min(cursor + 5, segment.operations().size());

        assertEquals(segment.operations().subList(cursor, end), segment.operations(cursor, 5));
        assertEquals(segment.operations(), segment.operations(0, segment.operations().size()));
        assertTrue(segment.operations(segment.operations().size(), 1).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> segment.operations(-1, 1));
    }

    @Test
    void oversizedRoomsClampAtBoundsInsteadOfLeavingThem() {
        TopoNode node = new TopoNode("test", NodeKind.CAVERN, "upper",
                new TopoPos(10, 12, 0), Set.of(), "test");
        TopologyPlan small = new TopologyPlan(1, "test", TIGHT_BOUNDS,
                java.util.List.of(node), java.util.List.of(), java.util.List.of("test"), "test", "test");

        var segment = SegmentGeometry.node(small, node, TIGHT_BOUNDS, ARRIVAL);
        assertFalse(segment.operations().isEmpty());
        for (var operation : segment.operations()) {
            assertTrue(TIGHT_BOUNDS.contains(operation.position()),
                    "clamped plan still wrote outside bounds at " + operation.position());
        }
    }

    private static Map<TopoPos, SegmentGeometry.BlockKind> asMap(SegmentGeometry.SegmentPlan segment) {
        Map<TopoPos, SegmentGeometry.BlockKind> blocks = new TreeMap<>(
                java.util.Comparator.comparingInt(TopoPos::y)
                        .thenComparingInt(TopoPos::z).thenComparingInt(TopoPos::x));
        for (var operation : segment.operations()) {
            assertFalse(blocks.containsKey(operation.position()), "duplicate block operation");
            blocks.put(operation.position(), operation.kind());
        }
        return blocks;
    }
}
