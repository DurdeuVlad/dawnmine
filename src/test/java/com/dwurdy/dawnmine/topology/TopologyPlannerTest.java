package com.dwurdy.dawnmine.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Deterministic and graph-property tests for {@link TopologyPlanner} over
 * fixed and randomized seeds, plus boundary and failure inputs. These run on
 * the plain JVM: the planner has no Minecraft or registry dependency.
 */
class TopologyPlannerTest {

    /** Mimics the M1 active region: a square of chunk columns centred on the
     * arrival point over the full mine dimension height (-64..319). */
    static TopoBounds bounds(int radiusChunks) {
        return new TopoBounds(-radiusChunks * 16, -64, -radiusChunks * 16,
                radiusChunks * 16 - 1, 319, radiusChunks * 16 - 1);
    }

    static TopologyProfile profile() {
        return TopologyProfile.builtin();
    }

    private static TopologyPlan plan(long seed, int radiusChunks) {
        return TopologyPlanner.plan(seed, profile(), bounds(radiusChunks));
    }

    // ------------------------------------------------------------------
    // Acceptance 1: same seed + config -> byte-for-byte identical output
    // ------------------------------------------------------------------

    @Test
    void sameSeedProducesByteIdenticalPlan() {
        TopologyPlan first = plan(42L, 64);
        TopologyPlan second = plan(42L, 64);
        assertEquals(first.canonical(), second.canonical(),
                "repeated generation of the same seed diverged");
        assertEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void nodeFeaturesCannotMutateAfterCanonicalFingerprintIsCached() {
        EnumSet<NodeFeature> callerFeatures = EnumSet.of(
                NodeFeature.CHECKPOINT, NodeFeature.DEAD_END);
        TopoNode node = new TopoNode("n000", NodeKind.ROUTE, "upper",
                new TopoPos(0, 40, 0), callerFeatures, "test");
        TopologyPlan plan = new TopologyPlan(7L, "standard", bounds(16),
                List.of(node), List.of(), List.of("n000"), "n000", "n000");
        String canonical = plan.canonical();
        String fingerprint = plan.fingerprint();

        callerFeatures.clear();
        assertTrue(node.has(NodeFeature.CHECKPOINT), "constructor retained caller's mutable set");
        assertThrows(UnsupportedOperationException.class, () -> node.features().clear());
        assertEquals(canonical, plan.canonical());
        assertEquals(fingerprint, plan.fingerprint());
    }

    @Test
    void determinismHoldsAcrossManySeeds() {
        Random rng = new Random(7L);
        for (int i = 0; i < 25; i++) {
            long seed = rng.nextLong();
            assertEquals(plan(seed, 64).canonical(), plan(seed, 64).canonical(),
                    "seed " + seed + " did not reproduce identically");
        }
    }

    @Test
    void differentSeedsProduceDifferentPlans() {
        assertNotEquals(plan(1L, 64).fingerprint(), plan(2L, 64).fingerprint());
        assertNotEquals(plan(1L, 64).fingerprint(), plan(1L, 48).fingerprint(),
                "bounds must feed the plan, not just the seed");
    }

    // ------------------------------------------------------------------
    // Property sweep: every invariant must hold for arbitrary seeds
    // ------------------------------------------------------------------

    @Test
    void randomizedSeedsAllValidate() {
        Random rng = new Random(0xC0FFEE);
        for (int i = 0; i < 96; i++) {
            long seed = rng.nextLong();
            TopologyPlan plan = plan(seed, 64);
            TopologyReport report = TopologyValidator.validate(plan, profile());
            assertTrue(report.ok(),
                    "seed " + seed + " produced an invalid plan: " + report.describe());
        }
    }

    @Test
    void fixedSeedsAllValidate() {
        for (long seed : new long[] {1L, 2L, 3L, 42L, 1337L, 20261102L, Long.MIN_VALUE, -1L}) {
            TopologyPlan plan = plan(seed, 64);
            TopologyReport report = TopologyValidator.validate(plan, profile());
            assertTrue(report.ok(), "seed " + seed + " invalid: " + report.describe());
        }
    }

    // ------------------------------------------------------------------
    // Acceptance 2/4/5: reachability, dead ends, regions, validation gate
    // ------------------------------------------------------------------

    @Test
    void everyNodeReachableFromEntrance() {
        TopologyPlan plan = plan(42L, 64);
        Set<String> reached = new HashSet<>();
        List<String> queue = new java.util.ArrayList<>(List.of(plan.entranceId()));
        while (!queue.isEmpty()) {
            String id = queue.remove(queue.size() - 1);
            if (!reached.add(id)) {
                continue;
            }
            for (TopoEdge edge : plan.adjacency().get(id)) {
                queue.add(edge.other(id));
            }
        }
        assertEquals(plan.nodes().size(), reached.size(), "unreachable nodes exist");
    }

    @Test
    void leavesAreIntentionalDeadEnds() {
        TopologyPlan plan = plan(1337L, 64);
        for (TopoNode node : plan.nodes()) {
            int degree = plan.adjacency().get(node.id()).size();
            if (degree == 1 && node.kind() != NodeKind.ENTRANCE) {
                assertTrue(node.has(NodeFeature.DEAD_END),
                        "unmarked dead end at " + node.id());
            }
        }
    }

    @Test
    void regionLabelsAndTransitionsHold() {
        TopologyPlan plan = plan(20261102L, 64);
        List<String> expected = List.of("upper", "middle", "chaos");
        assertEquals(Set.copyOf(expected), plan.regionIds(),
                "plan must span upper, middle, and Chaos regions");
        int last = -1;
        for (String id : plan.mainPath()) {
            int idx = expected.indexOf(plan.node(id).region());
            assertTrue(idx >= last, "main route went back up a region at " + id);
            last = Math.max(last, idx);
        }
    }

    @Test
    void checkpointsExistOnMainRoute() {
        TopologyPlan plan = plan(42L, 64);
        assertTrue(plan.checkpoints().size() >= 3,
                "expected at least one checkpoint per region");
        Set<String> path = new HashSet<>(plan.mainPath());
        for (TopoNode cp : plan.checkpoints()) {
            assertTrue(path.contains(cp.id()), "checkpoint off the main route");
        }
    }

    @Test
    void landmarksAndResourceFieldsPlanned() {
        TopologyPlan plan = plan(42L, 64);
        long landmarks = plan.nodes().stream()
                .filter(n -> n.has(NodeFeature.LANDMARK)).count();
        long fields = plan.nodes().stream()
                .filter(n -> n.has(NodeFeature.RESOURCE_FIELD)).count();
        assertTrue(landmarks >= 3, "expected landmarks in every region");
        assertTrue(fields >= 10, "expected resource fields in every region");
        assertFalse(plan.checkpoints().isEmpty());
    }

    // ------------------------------------------------------------------
    // Acceptance 3/6: bounds, budget, boundary edges, failure inputs
    // ------------------------------------------------------------------

    @Test
    void smallestConfiguredRegionStillPlans() {
        // mineRegionRadiusChunks floor is 16 -> a 256x256-block field.
        TopologyPlan plan = plan(42L, 16);
        TopologyReport report = TopologyValidator.validate(plan, profile());
        assertTrue(report.ok(), "16-chunk region failed: " + report.describe());
    }

    @Test
    void tinyBoundsFailCleanlyBeforePlacement() {
        TopologyException error = assertThrows(TopologyException.class,
                () -> plan(42L, 2));
        assertTrue(error.getMessage().contains("bounds")
                || error.getMessage().contains("margin"),
                "unexpected failure text: " + error.getMessage());
    }

    @Test
    void degenerateBoundsFailCleanly() {
        assertThrows(TopologyException.class,
                () -> TopologyPlanner.plan(1L, profile(), new TopoBounds(0, 0, 0, -1, 0, -1)));
        assertThrows(TopologyException.class,
                () -> TopologyPlanner.plan(1L, profile(), new TopoBounds(0, 300, 0, 100, 305, 100)));
    }

    @Test
    void invalidProfilesAreRejected() {
        TopologyProfile noRegions = new TopologyProfile("bad", 24, 3, 18, 44, 75,
                160, 260, 40000, 3, List.of());
        assertThrows(TopologyException.class,
                () -> TopologyPlanner.plan(1L, noRegions, bounds(64)));
        TopologyProfile inverted = new TopologyProfile("bad", 24, 3, 18, 44, 75,
                160, 260, 40000, 3,
                List.of(new TopologyProfile.RegionSpec("up", "Up", -40, 24,
                        8, 1, 1, 0, 1, 1, 0, 1, 0)));
        assertThrows(TopologyException.class,
                () -> TopologyPlanner.plan(1L, inverted, bounds(64)));
    }

    @Test
    void optionalNodeCountsAreBudgetedBeforePlanning() {
        TopologyProfile standard = profile();
        List<TopologyProfile.RegionSpec> regions = standard.regions().stream()
                .map(region -> region.id().equals("upper")
                        ? new TopologyProfile.RegionSpec(region.id(), region.name(),
                                region.topY(), region.bottomY(), region.mainRouteNodes(),
                                region.caverns(), region.branches(), region.loops(),
                                region.pockets(), 5000, region.landmarks(), region.checkpoints(), 0)
                        : region)
                .toList();
        TopologyProfile overBudget = new TopologyProfile(standard.id(),
                standard.boundsMargin(), standard.corridorHalfWidth(), standard.minStep(),
                standard.maxStep(), standard.maxTurnDeg(), standard.loopMaxDistance(),
                standard.maxNodes(), standard.maxCorridorLength(), standard.branchMaxLength(),
                regions);

        TopologyException error = assertThrows(TopologyException.class,
                () -> TopologyPlanner.plan(1L, overBudget, bounds(64)));

        assertTrue(error.getMessage().contains("conservative planned node bound"),
                "optional nodes were not rejected by preflight: " + error.getMessage());
        assertTrue(overBudget.maximumPlannedNodes() > overBudget.maxNodes());
    }

    @Test
    void loopRetryWorkIsBoundedBeforePlanning() {
        TopologyProfile standard = profile();
        List<TopologyProfile.RegionSpec> regions = standard.regions().stream()
                .map(region -> region.id().equals("chaos")
                        ? new TopologyProfile.RegionSpec(region.id(), region.name(),
                                region.topY(), region.bottomY(), region.mainRouteNodes(),
                                region.caverns(), region.branches(), 257, region.pockets(),
                                region.resourceFields(), region.landmarks(), region.checkpoints(), 0)
                        : region)
                .toList();
        TopologyProfile tooManyLoops = new TopologyProfile(standard.id(),
                standard.boundsMargin(), standard.corridorHalfWidth(), standard.minStep(),
                standard.maxStep(), standard.maxTurnDeg(), standard.loopMaxDistance(),
                standard.maxNodes(), standard.maxCorridorLength(), standard.branchMaxLength(),
                regions);

        TopologyException error = assertThrows(TopologyException.class,
                () -> TopologyPlanner.plan(1L, tooManyLoops, bounds(64)));

        assertTrue(error.getMessage().contains("hard limit of 256"), error.getMessage());
    }

    @Test
    void planContainsNoPrefabAsset() {
        // Topology output is a pure node/edge graph: no structure templates,
        // no palette references, nothing but coordinates and labels.
        TopologyPlan plan = plan(42L, 64);
        String canonical = plan.canonical();
        assertFalse(canonical.contains(".nbt"));
        assertFalse(canonical.contains("structure"));
    }
}
