package com.dwurdy.dawnmine.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.dawnmine.topology.TopoBounds;
import com.dwurdy.dawnmine.topology.TopologyPlan;
import com.dwurdy.dawnmine.topology.TopologyPlanner;
import com.dwurdy.dawnmine.topology.TopologyProfile;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PreparationPlanTest {
    private static final TopoBounds BOUNDS = new TopoBounds(-1024, -64, -1024, 1023, 319, 1023);

    @Test
    void expectedSetCoversConnectorAllNodesAndEdgesInAllPhases() {
        TopologyPlan plan = TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
        List<String> expected = PreparationPlan.expectedSegments(plan);
        int structural = 1 + plan.nodes().size() + plan.edges().size();
        long safehouses = plan.nodes().stream()
                .filter(n -> n.has(com.dwurdy.dawnmine.topology.NodeFeature.SAFEHOUSE))
                .count();
        // +1 for the hub build, +N for each static landmark segment.
        assertEquals(structural * 3 + 1 + safehouses, expected.size());
        // Connector first, structural before dress before ore.
        assertEquals("connector:arrival:" + plan.entranceId(), expected.get(0));
        assertTrue(expected.subList(0, structural).stream()
                .noneMatch(PreparationPlan::isDressSegment));
        assertTrue(expected.subList(0, structural).stream()
                .noneMatch(PreparationPlan::isOreSegment));
        // Hub sits after all structural carves, landmarks after the hub,
        // and both before dressing.
        assertEquals("hub", expected.get(structural));
        int dressStart = structural + 1 + (int) safehouses;
        assertTrue(expected.subList(structural + 1, dressStart).stream()
                .allMatch(id -> id.startsWith("landmark:")));
        assertTrue(expected.subList(dressStart, dressStart + structural).stream()
                .allMatch(PreparationPlan::isDressSegment));
        assertTrue(expected.subList(dressStart + structural, expected.size()).stream()
                .allMatch(PreparationPlan::isOreSegment));
        for (var node : plan.nodes()) {
            assertTrue(expected.contains("node:" + node.id()));
            assertTrue(expected.contains("dress:node:" + node.id()));
            assertTrue(expected.contains("ores:node:" + node.id()));
            if (node.has(com.dwurdy.dawnmine.topology.NodeFeature.SAFEHOUSE)) {
                assertTrue(expected.contains("landmark:" + node.id()));
            }
        }
        for (var edge : plan.edges()) {
            String id = "edge:" + edge.a() + ":" + edge.b();
            assertTrue(expected.contains(id));
            assertTrue(expected.contains("dress:" + id));
            assertTrue(expected.contains("ores:" + id));
        }
    }

    @Test
    void incompleteReceiptsDenyAdmission() {
        TopologyPlan plan = TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
        List<String> expected = PreparationPlan.expectedSegments(plan);
        Set<String> completed = new HashSet<>(expected.subList(0, expected.size() - 1));
        var progress = PreparationPlan.progress(plan, completed, 1, "ores:x");
        assertFalse(progress.complete());
        completed.add(expected.get(expected.size() - 1));
        progress = PreparationPlan.progress(plan, completed, 0, "");
        assertTrue(progress.complete());
        assertEquals(100, progress.percent());
    }

    @Test
    void expectedSetIsDeterministicAcrossReplan() {
        TopologyPlan a = TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
        TopologyPlan b = TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
        assertEquals(PreparationPlan.expectedSegments(a), PreparationPlan.expectedSegments(b));
    }
}
