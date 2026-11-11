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

    private static Set<String> safehouseIds(TopologyPlan plan) {
        Set<String> ids = new HashSet<>();
        for (var node : plan.nodes()) {
            if (node.has(com.dwurdy.dawnmine.topology.NodeFeature.SAFEHOUSE)) {
                ids.add(node.id());
            }
        }
        return ids;
    }

    @Test
    void mineV2PreparationIsJustTheHub() {
        // Terrain comes from the chunk generator (CaveTerrain); only the hub is a placed segment.
        TopologyPlan plan = TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
        assertEquals(List.of("hub"), PreparationPlan.expectedSegments(plan, safehouseIds(plan)));
        assertEquals(List.of("hub"), PreparationPlan.expectedSegments(plan, Set.of()));
    }

    @Test
    void incompleteReceiptsDenyAdmission() {
        TopologyPlan plan = TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
        Set<String> placed = safehouseIds(plan);
        List<String> expected = PreparationPlan.expectedSegments(plan, placed);
        Set<String> completed = new HashSet<>(expected.subList(0, expected.size() - 1));
        var progress = PreparationPlan.progress(plan, placed, completed, 1, "ores:x");
        assertFalse(progress.complete());
        completed.add(expected.get(expected.size() - 1));
        progress = PreparationPlan.progress(plan, placed, completed, 0, "");
        assertTrue(progress.complete());
        assertEquals(100, progress.percent());
    }

    @Test
    void expectedSetIsDeterministicAcrossReplan() {
        TopologyPlan a = TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
        TopologyPlan b = TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
        Set<String> placed = safehouseIds(a);
        assertEquals(PreparationPlan.expectedSegments(a, placed),
                PreparationPlan.expectedSegments(b, placed));
    }
}
