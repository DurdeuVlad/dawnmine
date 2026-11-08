package com.dwurdy.dawnmine.generation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.dwurdy.dawnmine.generation.LandmarkCatalog.Definition;
import com.dwurdy.dawnmine.topology.TopoBounds;
import com.dwurdy.dawnmine.topology.TopoEdge;
import com.dwurdy.dawnmine.topology.TopoNode;
import com.dwurdy.dawnmine.topology.TopoPos;
import com.dwurdy.dawnmine.topology.TopologyPlan;
import com.dwurdy.dawnmine.topology.TopologyPlanner;
import com.dwurdy.dawnmine.topology.TopologyProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression for the live-carve failure: landmark edges routed reserve-blind
 * because the reserve predicate read the not-yet-populated network cache,
 * producing centreline cells inside safehouse reserves that resolution then
 * could not reconcile ("smoothing would raise the fixed hub anchor"). The
 * avoidance must come from the layout under construction, never the cache.
 */
class ReproLiveFailureTest {
    private static final TopoBounds BOUNDS = new TopoBounds(-1024, -64, -1024, 1023, 319, 1023);
    private static final TopoPos ARRIVAL = new TopoPos(0, 42, 8);
    private static final TopoPos SHELTER_FEET = new TopoPos(400, 10, 400);

    @BeforeEach
    void installFixture() {
        LandmarkCatalog.installForTest(Map.of("shelter", shelter()));
        PassageNetwork.clearCache();
    }

    @AfterEach
    void clearFixture() {
        LandmarkCatalog.installForTest(Map.of());
        PassageNetwork.clearCache();
    }

    private static Definition shelter() {
        return LandmarkCatalog.parse(JsonParser.parseString("""
                {
                  "id": "shelter",
                  "footprint": { "halfX": 2, "halfZ": 1, "height": 3, "reserveMargin": 1 },
                  "connectors": [
                    { "x": 2, "y": 1, "z": 0, "facing": "east" }
                  ],
                  "slots": { "S": "minecraft:stone_bricks", "A": "minecraft:air" },
                  "layers": [
                    [ "SSSSS", "SSSSS", "SSSSS" ],
                    [ "SSSSS", "SAAAA", "SSSSA" ],
                    [ "SSSSS", "SAAAA", "SSSSA" ]
                  ]
                }
                """).getAsJsonObject());
    }

    @Test
    void avoidLandmarkReserveRoutesOutOfTheSuppliedLayout() {
        LandmarkLayout layout = LandmarkLayout.place(
                Map.of("n01", SHELTER_FEET), Map.of("n01", "shelter"),
                LandmarkCatalog.definitions());
        // A straight run straight through the shelter's reserve box.
        List<TopoPos> cells = new ArrayList<>();
        for (int x = 392; x <= 408; x++) {
            cells.add(new TopoPos(x, 10, 400));
        }
        List<TopoPos> routed = SegmentGeometry.avoidLandmarkReserve(
                cells, layout);
        for (TopoPos cell : routed) {
            assertFalse(layout.isReserved(cell),
                    "routed cell still inside the reserve: " + cell);
        }
    }

    @Test
    void entranceLandmarkReserveRoutesOutOfTheSuppliedLayout() {
        LandmarkLayout layout = LandmarkLayout.place(
                Map.of("n01", SHELTER_FEET), Map.of("n01", "shelter"),
                LandmarkCatalog.definitions());
        List<TopoPos> cells = new ArrayList<>();
        for (int x = 392; x <= 408; x++) {
            cells.add(new TopoPos(x, 10, 400));
        }
        List<TopoPos> routed = SegmentGeometry.avoidEntranceLandmarkReserve(
                cells, layout);
        for (TopoPos cell : routed) {
            assertFalse(layout.isReserved(cell),
                    "entrance-landmark routed cell still inside the reserve: " + cell);
        }
    }

    @Test
    void resolvedSafehouseEdgesRespectReservesAndConnectors() {
        TopologyPlan plan = TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
        PassageNetwork network = PassageNetwork.of(plan, ARRIVAL);
        LandmarkLayout layout = network.landmarks();
        assertFalse(layout.placements().isEmpty(),
                "fixture expects at least one placed safehouse");
        int landmarkEdges = 0;
        for (TopoEdge edge : plan.edges()) {
            List<TopoPos> path = network.path(SegmentGeometry.edgeSegmentId(edge));
            Set<TopoPos> ownOpenings = layout.openingsFor(edge.a(), edge.b());
            for (int i = 1; i < path.size() - 1; i++) {
                TopoPos cell = path.get(i);
                assertTrue(!layout.isReserved(cell) || ownOpenings.contains(cell),
                        "edge " + SegmentGeometry.edgeSegmentId(edge)
                                + " passes through a reserve at " + cell);
            }
            if (layout.placement(edge.a()) != null || layout.placement(edge.b()) != null) {
                landmarkEdges++;
                if (layout.placement(edge.a()) != null) {
                    assertTrue(layout.connectors(edge.a()).contains(path.get(0)),
                            "edge " + SegmentGeometry.edgeSegmentId(edge)
                                    + " does not start at a declared connector of " + edge.a());
                }
                if (layout.placement(edge.b()) != null) {
                    assertTrue(layout.connectors(edge.b())
                                    .contains(path.get(path.size() - 1)),
                            "edge " + SegmentGeometry.edgeSegmentId(edge)
                                    + " does not end at a declared connector of " + edge.b());
                }
            }
        }
        assertTrue(landmarkEdges > 0, "fixture expects edges touching safehouses");
    }

    @Test
    void liveSeedWithRealShelterResolves() throws Exception {
        // The exact live-run fixture that failed "crossing merge would
        // lower -4,46,70 by 13 blocks": run 2026-11-08-21, seed
        // -2856163543928830103, 274 nodes. The merge could only converge
        // down before; the cluster top is a legal shared floor.
        var json = JsonParser.parseString(java.nio.file.Files.readString(
                java.nio.file.Path.of(
                        "src/main/resources/data/dawnmine/landmarks/prospect_shelter.json")))
                .getAsJsonObject();
        LandmarkCatalog.installForTest(Map.of(
                "prospect_shelter", LandmarkCatalog.parse(json)));
        try {
            // Historical live seeds resolved at draw 0 - pinning that keeps
            // the draw machinery honest: a regression that makes more draws
            // unsatisfiable cannot hide behind later draws succeeding.
            for (long seed : new long[] {-2856163543928830103L,
                    -5038305720449563874L}) {
                PassageNetwork.clearCache();
                PassageNetwork network = PassageNetwork.of(TopologyPlanner.plan(
                        com.dwurdy.dawnmine.topology.MineTopology.drawSeed(seed, 0),
                        TopologyProfile.builtin(), BOUNDS), ARRIVAL);
                assertFalse(network.landmarks().placements().isEmpty(),
                        "live seed " + seed + " expects placed safehouses");
            }
            // Run -26's base draw was unsatisfiable (bound-pinned door cell
            // vs ceiling-capped column) - the contract is on the draw
            // sequence: at least one draw must resolve.
            {
                PassageNetwork network = null;
                IllegalStateException failure = null;
                for (int draw = 0; draw < 4 && network == null; draw++) {
                    PassageNetwork.clearCache();
                    try {
                        network = PassageNetwork.of(TopologyPlanner.plan(
                                com.dwurdy.dawnmine.topology.MineTopology
                                        .drawSeed(3707164540949967090L, draw),
                                TopologyProfile.builtin(), BOUNDS), ARRIVAL);
                    } catch (IllegalStateException x) {
                        failure = x;
                    }
                }
                if (network == null) {
                    throw failure;
                }
                assertFalse(network.landmarks().placements().isEmpty(),
                        "live seed 3707164540949967090 expects placed safehouses");
            }
        } finally {
            LandmarkCatalog.installForTest(Map.of());
        }
    }

    @Test
    void segmentPlansNeverWriteForeignCellsInsideReserves() {
        // Live-run 2026-11-08-24 regression: a mineshaft dress rail landed on
        // the connector door cell - openings were exempt from write
        // protection for ANY writer, not just the corridor that owns them.
        // Contract: only the edge terminating at a safehouse may write its
        // openings, and those writes are the approach's air (OPEN). Dress
        // and node plans may never emit anything inside a landmark reserve.
        TopologyPlan plan = TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
        PassageNetwork network = PassageNetwork.of(plan, ARRIVAL);
        LandmarkLayout layout = network.landmarks();
        assertFalse(layout.placements().isEmpty(),
                "fixture expects at least one placed safehouse");
        for (TopoEdge edge : plan.edges()) {
            String edgeId = SegmentGeometry.edgeSegmentId(edge);
            Set<TopoPos> ownOpenings = layout.openingsFor(edge.a(), edge.b());
            for (SegmentGeometry.BlockOperation op
                    : SegmentGeometry.edge(plan, edge, BOUNDS, ARRIVAL).operations()) {
                if (layout.isReserved(op.position())) {
                    assertTrue(ownOpenings.contains(op.position()),
                            edgeId + " writes into a foreign reserve cell "
                                    + op.position());
                    assertTrue(op.kind() == SegmentGeometry.BlockKind.OPEN,
                            edgeId + " writes non-air " + op.kind()
                                    + " into its own opening " + op.position());
                }
            }
            SegmentGeometry.SegmentPlan dress =
                    SegmentGeometry.dress(plan, edgeId, BOUNDS, ARRIVAL);
            for (SegmentGeometry.BlockOperation op : dress.operations()) {
                if (layout.isReserved(op.position())) {
                    assertTrue(ownOpenings.contains(op.position())
                                    && op.kind() == SegmentGeometry.BlockKind.OPEN,
                            "dress " + edgeId + " writes " + op.kind()
                                    + " inside a landmark reserve at "
                                    + op.position());
                }
            }
        }
        for (TopoNode node : plan.nodes()) {
            for (SegmentGeometry.BlockOperation op
                    : SegmentGeometry.node(plan, node, BOUNDS, ARRIVAL).operations()) {
                assertFalse(layout.isReserved(op.position()),
                        "node:" + node.id() + " writes " + op.kind()
                                + " inside a landmark reserve at " + op.position());
            }
        }
    }

    @Test
    void foreignNodePositionsNeverSitInsideReserves() {
        TopologyPlan plan = TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
        PassageNetwork network = PassageNetwork.of(plan, ARRIVAL);
        LandmarkLayout layout = network.landmarks();
        for (LandmarkLayout.Placement p : layout.placements().values()) {
            for (TopoNode node : plan.nodes()) {
                if (!node.id().equals(p.nodeId())) {
                    assertFalse(p.insideReserve(node.pos()),
                            "reserve of " + p.nodeId() + " covers foreign node "
                                    + node.id());
                }
            }
        }
    }
}
