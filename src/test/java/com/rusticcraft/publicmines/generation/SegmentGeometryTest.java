package com.rusticcraft.publicmines.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rusticcraft.publicmines.topology.NodeFeature;
import com.rusticcraft.publicmines.topology.NodeKind;
import com.rusticcraft.publicmines.topology.TopoBounds;
import com.rusticcraft.publicmines.topology.TopoNode;
import com.rusticcraft.publicmines.topology.TopoPos;
import com.rusticcraft.publicmines.topology.TopologyPlan;
import com.rusticcraft.publicmines.topology.TopologyPlanner;
import com.rusticcraft.publicmines.topology.TopologyProfile;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class SegmentGeometryTest {
    private static final TopoBounds BOUNDS = new TopoBounds(-1024, -64, -1024, 1023, 319, 1023);
    private static final TopoBounds TIGHT_BOUNDS = new TopoBounds(-12, 0, -12, 12, 24, 12);
    private static final TopoPos ARRIVAL = new TopoPos(0, 63, 0);

    private static TopologyPlan plan() {
        return TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
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
    void nodeAndEdgeOperationsStayInsideBoundsAndOutsideArrivalKeepOut() {
        TopologyPlan plan = plan();
        var segments = new SegmentGeometry.SegmentPlan[] {
                SegmentGeometry.node(plan, plan.node("m000"), BOUNDS, ARRIVAL),
                SegmentGeometry.edge(plan, plan.edges().get(0), BOUNDS, ARRIVAL)
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
        TopoPos feet = checkpoint.pos();

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
            assertEquals(SegmentGeometry.BlockKind.ROCK, blocks.get(node.pos().offset(0, -1, 0)),
                    "node lacks floor support: " + node.id());
            assertEquals(SegmentGeometry.BlockKind.OPEN, blocks.get(node.pos()),
                    "node center is blocked: " + node.id());
            assertEquals(SegmentGeometry.BlockKind.OPEN, blocks.get(node.pos().offset(0, 1, 0)),
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

        assertEquals(SegmentGeometry.BlockKind.OPEN, blocks.get(plan.node(edge.a()).pos()));
        assertEquals(SegmentGeometry.BlockKind.OPEN, blocks.get(plan.node(edge.b()).pos()));
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
        TopoPos source = cavern.pos().offset(0, -2, 0);

        assertEquals(SegmentGeometry.BlockKind.HAZARD_SOURCE, blocks.get(source));
        assertEquals(SegmentGeometry.BlockKind.HAZARD_BOUNDARY, blocks.get(source.offset(1, 0, 0)));
        assertEquals(SegmentGeometry.BlockKind.HAZARD_BOUNDARY, blocks.get(source.offset(-1, 0, 0)));
        assertEquals(SegmentGeometry.BlockKind.HAZARD_BOUNDARY, blocks.get(source.offset(0, 0, 1)));
        assertEquals(SegmentGeometry.BlockKind.HAZARD_BOUNDARY, blocks.get(source.offset(0, 0, -1)));
        assertEquals(SegmentGeometry.BlockKind.HAZARD_BOUNDARY, blocks.get(source.offset(0, -1, 0)));
    }

    @Test
    void entranceConnectorWalksDownFromThePadEdgeWithoutChangingThePad() {
        TopologyPlan plan = plan();
        var connector = SegmentGeometry.entryConnector(plan, BOUNDS, ARRIVAL);
        Map<TopoPos, SegmentGeometry.BlockKind> blocks = asMap(connector);

        assertEquals("connector:arrival:m000", connector.id());
        assertEquals(SegmentGeometry.BlockKind.ROCK,
                blocks.get(new TopoPos(3, 62, 0)), "connector must create a safe first step");
        assertEquals(SegmentGeometry.BlockKind.OPEN,
                blocks.get(new TopoPos(3, 63, 0)), "connector must open from the pad edge");
        assertEquals(SegmentGeometry.BlockKind.OPEN,
                blocks.get(plan.node("m000").pos()), "connector must reach the entrance chamber");
        assertTrue(connector.operations().stream().noneMatch(operation ->
                SegmentGeometry.isArrivalKeepOut(operation.position(), ARRIVAL)),
                "connector wrote into the reserved pad or its player clearance");
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
    void noSegmentMayBePartiallyClippedAtBounds() {
        TopoNode node = new TopoNode("test", NodeKind.CAVERN, "upper",
                new TopoPos(10, 12, 0), Set.of(), "test");
        TopologyPlan small = new TopologyPlan(1, "test", TIGHT_BOUNDS,
                java.util.List.of(node), java.util.List.of(), java.util.List.of("test"), "test", "test");

        assertThrows(IllegalArgumentException.class,
                () -> SegmentGeometry.node(small, node, TIGHT_BOUNDS, ARRIVAL));
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
