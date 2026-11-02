package com.rusticcraft.publicmines.topology;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Adversarial tests for {@link TopologyValidator}: hand-built broken plans
 * must each produce a human-readable failure instead of passing the
 * pre-mutation gate.
 */
class TopologyValidatorTest {

    private static final TopologyProfile PROFILE = TopologyProfile.builtin();
    private static final TopoBounds BOUNDS = TopologyPlannerTest.bounds(64);

    private static TopoNode node(String id, NodeKind kind, String region, int x, int y, int z,
                                 Set<NodeFeature> features) {
        return new TopoNode(id, kind, region, new TopoPos(x, y, z), features, "");
    }

    /** A 4-node main route spanning all three regions. Valid on its own and
     * used as the base for each broken variant below. */
    private static TopologyPlan minimalValidPlan() {
        List<TopoNode> nodes = List.of(
                node("m000", NodeKind.ENTRANCE, "upper", 0, 50, 0,
                        Set.of(NodeFeature.CHECKPOINT)),
                node("m001", NodeKind.ROUTE, "upper", 40, 30, 0, Set.of()),
                node("m002", NodeKind.ROUTE, "middle", 80, 0, 0, Set.of()),
                node("m003", NodeKind.ROUTE, "chaos", 120, -40, 0,
                        Set.of(NodeFeature.DEAD_END)));
        List<TopoEdge> edges = List.of(
                new TopoEdge("m000", "m001", EdgeKind.MAIN),
                new TopoEdge("m001", "m002", EdgeKind.MAIN),
                new TopoEdge("m002", "m003", EdgeKind.MAIN));
        return new TopologyPlan(1L, PROFILE.id(), BOUNDS, nodes, edges,
                List.of("m000", "m001", "m002", "m003"), "m000", "m003");
    }

    private static boolean hasProblem(TopologyReport report, String fragment) {
        return report.problems().stream().anyMatch(p -> p.contains(fragment));
    }

    @Test
    void minimalConnectedPlanPasses() {
        TopologyReport report = TopologyValidator.validate(minimalValidPlan(), PROFILE);
        assertTrue(report.ok(), "minimal plan rejected: " + report.describe());
    }

    @Test
    void unreachableNodeFails() {
        TopologyPlan base = minimalValidPlan();
        List<TopoNode> nodes = new ArrayList<>(base.nodes());
        nodes.add(node("p000", NodeKind.POCKET, "upper", 200, 40, 0,
                Set.of(NodeFeature.DEAD_END)));
        TopologyPlan broken = new TopologyPlan(1L, PROFILE.id(), BOUNDS, nodes,
                base.edges(), base.mainPath(), base.entranceId(), base.terminusId());
        TopologyReport report = TopologyValidator.validate(broken, PROFILE);
        assertFalse(report.ok());
        assertTrue(hasProblem(report, "unreachable"), report.describe());
    }

    @Test
    void duplicateCoordinatesFail() {
        TopologyPlan base = minimalValidPlan();
        List<TopoNode> nodes = new ArrayList<>(base.nodes());
        nodes.add(node("x001", NodeKind.POCKET, "upper", 40, 30, 0,
                Set.of(NodeFeature.DEAD_END)));
        List<TopoEdge> edges = new ArrayList<>(base.edges());
        edges.add(new TopoEdge("m000", "x001", EdgeKind.SPUR));
        TopologyPlan broken = new TopologyPlan(1L, PROFILE.id(), BOUNDS, nodes,
                edges, base.mainPath(), base.entranceId(), base.terminusId());
        TopologyReport report = TopologyValidator.validate(broken, PROFILE);
        assertFalse(report.ok());
        assertTrue(hasProblem(report, "position"), report.describe());
    }

    @Test
    void outOfBoundsNodeFails() {
        List<TopoNode> nodes = List.of(
                node("m000", NodeKind.ENTRANCE, "upper", 0, 50, 0,
                        Set.of(NodeFeature.CHECKPOINT)),
                node("m001", NodeKind.ROUTE, "chaos", 2000, -40, 0,
                        Set.of(NodeFeature.DEAD_END)));
        TopologyPlan broken = new TopologyPlan(1L, PROFILE.id(), BOUNDS, nodes,
                List.of(new TopoEdge("m000", "m001", EdgeKind.MAIN)),
                List.of("m000", "m001"), "m000", "m001");
        TopologyReport report = TopologyValidator.validate(broken, PROFILE);
        assertFalse(report.ok());
        assertTrue(hasProblem(report, "bounds"), report.describe());
    }

    @Test
    void unmarkedLeafFails() {
        TopologyPlan base = minimalValidPlan();
        List<TopoNode> nodes = new ArrayList<>(base.nodes());
        nodes.add(node("b000-0", NodeKind.CAVE, "upper", 60, 40, 20, Set.of()));
        List<TopoEdge> edges = new ArrayList<>(base.edges());
        edges.add(new TopoEdge("b000-0", "m001", EdgeKind.BRANCH));
        TopologyPlan broken = new TopologyPlan(1L, PROFILE.id(), BOUNDS, nodes,
                edges, base.mainPath(), base.entranceId(), base.terminusId());
        TopologyReport report = TopologyValidator.validate(broken, PROFILE);
        assertFalse(report.ok());
        assertTrue(hasProblem(report, "dead end"), report.describe());
    }

    @Test
    void wrongBandNodeFails() {
        // A node labelled "chaos" but sitting at upper-depth Y is a bad
        // region assignment even though everything else connects.
        List<TopoNode> nodes = List.of(
                node("m000", NodeKind.ENTRANCE, "upper", 0, 50, 0,
                        Set.of(NodeFeature.CHECKPOINT)),
                node("m001", NodeKind.ROUTE, "chaos", 40, 40, 0,
                        Set.of(NodeFeature.DEAD_END)));
        TopologyPlan broken = new TopologyPlan(1L, PROFILE.id(), BOUNDS, nodes,
                List.of(new TopoEdge("m000", "m001", EdgeKind.MAIN)),
                List.of("m000", "m001"), "m000", "m001");
        TopologyReport report = TopologyValidator.validate(broken, PROFILE);
        assertFalse(report.ok());
        assertTrue(hasProblem(report, "band"), report.describe());
    }

    @Test
    void brokenMainRouteContinuityFails() {
        TopologyPlan base = minimalValidPlan();
        // mainPath claims a route but the MAIN edges are missing entirely.
        TopologyPlan broken = new TopologyPlan(1L, PROFILE.id(), BOUNDS,
                base.nodes(), List.of(), base.mainPath(),
                base.entranceId(), base.terminusId());
        TopologyReport report = TopologyValidator.validate(broken, PROFILE);
        assertFalse(report.ok());
        assertTrue(hasProblem(report, "unreachable") || hasProblem(report, "MAIN"),
                report.describe());
    }

    @Test
    void missingEdgeEndpointFailsWithoutThrowing() {
        TopologyPlan base = minimalValidPlan();
        List<TopoEdge> edges = new ArrayList<>(base.edges());
        edges.add(new TopoEdge("missing-node", "m001", EdgeKind.BRANCH));
        TopologyPlan broken = new TopologyPlan(1L, PROFILE.id(), BOUNDS,
                base.nodes(), edges, base.mainPath(), base.entranceId(), base.terminusId());

        TopologyReport report = TopologyValidator.validate(broken, PROFILE);

        assertFalse(report.ok());
        assertTrue(hasProblem(report, "references a missing node"), report.describe());
    }

    @Test
    void checkpointOffMainRouteFails() {
        TopologyPlan base = minimalValidPlan();
        List<TopoNode> nodes = new ArrayList<>(base.nodes());
        nodes.add(node("b000-0", NodeKind.CAVE, "upper", 60, 40, 20,
                Set.of(NodeFeature.CHECKPOINT, NodeFeature.DEAD_END)));
        List<TopoEdge> edges = new ArrayList<>(base.edges());
        edges.add(new TopoEdge("b000-0", "m001", EdgeKind.BRANCH));
        TopologyPlan broken = new TopologyPlan(1L, PROFILE.id(), BOUNDS, nodes,
                edges, base.mainPath(), base.entranceId(), base.terminusId());
        TopologyReport report = TopologyValidator.validate(broken, PROFILE);
        assertFalse(report.ok());
        assertTrue(hasProblem(report, "main route"), report.describe());
    }

    @Test
    void oversizedPlanFailsBudget() {
        TopologyProfile tiny = new TopologyProfile("tiny", 24, 3, 18, 44, 75,
                160, 2, 40000, 3,
                List.of(new TopologyProfile.RegionSpec("upper", "U", 56, 24, 1,
                        0, 0, 0, 0, 0, 0, 0)));
        TopologyReport report = TopologyValidator.validate(minimalValidPlan(), tiny);
        assertFalse(report.ok(), "over-budget plan accepted");
        assertTrue(hasProblem(report, "budget"), report.describe());
    }

    @Test
    void validatorRejectsProfileMissingRequiredDepthTiers() {
        TopologyProfile incomplete = new TopologyProfile("incomplete", 24, 3, 18, 44, 75,
                160, 260, 40000, 3,
                List.of(new TopologyProfile.RegionSpec("upper", "Upper", 56, 24, 1,
                        0, 0, 0, 0, 0, 0, 0)));

        TopologyReport report = TopologyValidator.validate(minimalValidPlan(), incomplete);

        assertFalse(report.ok());
        assertTrue(hasProblem(report, "profile: profile is missing required 'middle' depth tier"),
                report.describe());
        assertTrue(hasProblem(report, "profile: profile is missing required 'chaos' depth tier"),
                report.describe());
    }
}
