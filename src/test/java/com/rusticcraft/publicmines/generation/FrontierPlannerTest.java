package com.rusticcraft.publicmines.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rusticcraft.publicmines.topology.EdgeKind;
import com.rusticcraft.publicmines.topology.NodeFeature;
import com.rusticcraft.publicmines.topology.NodeKind;
import com.rusticcraft.publicmines.topology.TopoBounds;
import com.rusticcraft.publicmines.topology.TopoEdge;
import com.rusticcraft.publicmines.topology.TopoNode;
import com.rusticcraft.publicmines.topology.TopoPos;
import com.rusticcraft.publicmines.topology.TopologyPlan;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FrontierPlannerTest {
    @Test
    void nearbyCompletedNodesExposeOnlyAdjacentEdgesInStableOrder() {
        TopoNode entrance = node("m000", 0, 20, 0);
        TopoNode nearby = node("m001", 10, 20, 0);
        TopoNode distant = node("m002", 80, 20, 0);
        TopologyPlan plan = new TopologyPlan(1L, "test",
                new TopoBounds(-128, -64, -128, 127, 319, 127),
                List.of(entrance, nearby, distant),
                List.of(new TopoEdge("m001", "m002", EdgeKind.MAIN),
                        new TopoEdge("m000", "m001", EdgeKind.MAIN)),
                List.of("m000", "m001", "m002"), "m000", "m002");

        List<String> edges = FrontierPlanner.adjacentEdges(plan,
                List.of(distant, nearby, entrance, nearby), Set.of("m000", "m001"),
                0.5, 20, 0.5, 28);

        assertEquals(List.of("edge:m000:m001", "edge:m001:m002"), edges);
    }

    @Test
    void distanceIncludesVerticalPositionAndHonorsRadiusBoundary() {
        TopoNode node = node("m000", 0, 20, 0);
        TopoNode next = node("m001", 1, 20, 0);
        TopologyPlan plan = new TopologyPlan(1L, "test",
                new TopoBounds(-16, -64, -16, 15, 319, 15),
                List.of(node, next), List.of(new TopoEdge("m000", "m001", EdgeKind.MAIN)),
                List.of("m000", "m001"), "m000", "m001");

        assertEquals(List.of("edge:m000:m001"), FrontierPlanner.adjacentEdges(plan, List.of(node),
                Set.of("m000"), 0.5, 48, 0.5, 28));
        assertEquals(List.of(), FrontierPlanner.adjacentEdges(plan, List.of(node),
                Set.of("m000"), 0.5, 48.01, 0.5, 28));
    }

    private static TopoNode node(String id, int x, int y, int z) {
        return new TopoNode(id, NodeKind.ROUTE, "upper", new TopoPos(x, y, z),
                EnumSet.noneOf(NodeFeature.class), id);
    }
}
