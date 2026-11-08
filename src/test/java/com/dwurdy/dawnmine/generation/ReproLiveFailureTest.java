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
                    [ "SSSSS", "SAAAS", "SSSSA" ],
                    [ "SSSSS", "SAAAS", "SSSSA" ]
                  ]
                }
                """).getAsJsonObject());
    }

    @Test
    void avoidLandmarkReserveRoutesOutOfTheSuppliedLayout() {
        LandmarkLayout layout = LandmarkLayout.place(
                Map.of("n01", SHELTER_FEET), Map.of("n01", "shelter"));
        // A straight run straight through the shelter's reserve box.
        List<TopoPos> cells = new ArrayList<>();
        for (int x = 392; x <= 408; x++) {
            cells.add(new TopoPos(x, 10, 400));
        }
        List<TopoPos> routed = SegmentGeometry.avoidLandmarkReserve(
                cells, Set.of(), layout);
        for (TopoPos cell : routed) {
            assertFalse(layout.isReserved(cell),
                    "routed cell still inside the reserve: " + cell);
        }
    }

    @Test
    void entranceLandmarkReserveRoutesOutOfTheSuppliedLayout() {
        LandmarkLayout layout = LandmarkLayout.place(
                Map.of("n01", SHELTER_FEET), Map.of("n01", "shelter"));
        List<TopoPos> cells = new ArrayList<>();
        for (int x = 392; x <= 408; x++) {
            cells.add(new TopoPos(x, 10, 400));
        }
        List<TopoPos> routed = SegmentGeometry.avoidEntranceLandmarkReserve(
                cells, Set.of(), layout);
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
            TopologyPlan plan = TopologyPlanner.plan(-2856163543928830103L,
                    TopologyProfile.builtin(), BOUNDS);
            PassageNetwork network = PassageNetwork.of(plan, ARRIVAL);
            assertFalse(network.landmarks().placements().isEmpty(),
                    "live seed expects placed safehouses");
        } finally {
            LandmarkCatalog.installForTest(Map.of());
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
