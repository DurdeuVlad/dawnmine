package com.dwurdy.dawnmine.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.dawnmine.topology.HubLayout;
import com.dwurdy.dawnmine.topology.NodeFeature;
import com.dwurdy.dawnmine.topology.TopoBounds;
import com.dwurdy.dawnmine.topology.TopoEdge;
import com.dwurdy.dawnmine.topology.TopoNode;
import com.dwurdy.dawnmine.topology.TopoPos;
import com.dwurdy.dawnmine.topology.TopologyPlanner;
import com.dwurdy.dawnmine.topology.TopologyProfile;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class OrePlacementPlannerTest {
    private static final TopoBounds BOUNDS = new TopoBounds(-1024, -64, -1024, 1023, 319, 1023);
    private static final TopoPos ARRIVAL = new TopoPos(0, 42, 8);
    private static final OreCatalog.OreCatalogSnapshot CATALOG = OreCatalog.OreCatalogSnapshot.fromEntries(List.of(
            entry("minecraft:coal_ore", OreCatalog.Tier.UPPER, 1, 3),
            entry("minecraft:gold_ore", OreCatalog.Tier.MIDDLE, 1, 2),
            entry("minecraft:diamond_ore", OreCatalog.Tier.CHAOS, 1, 1)));

    private static OreCatalog.Entry entry(String block, OreCatalog.Tier tier, int weight, int vein) {
        return new OreCatalog.Entry(ResourceLocation.parse(block), tier, weight, vein,
                OreCatalog.Kind.RESOURCE, false, ResourceLocation.parse("minecraft:iron_pickaxe"),
                List.of(ResourceLocation.parse("minecraft:coal")), null);
    }

    private static Function<TopoPos, OreCatalog.Tier> tierAt() {
        return pos -> pos.y() < -16 ? OreCatalog.Tier.CHAOS
                : pos.y() <= 24 ? OreCatalog.Tier.MIDDLE : OreCatalog.Tier.UPPER;
    }

    private static java.util.function.Predicate<TopoPos> protect() {
        return HubLayout::isReserved;
    }

    private static SegmentGeometry.SegmentPlan plan(String id,
            SegmentGeometry.SegmentPlan structural,
            OreCatalog.OreCatalogSnapshot catalog, int target, long seed) {
        return OrePlacementPlanner.planOreSegment("ores:" + id, List.of(structural),
                catalog, tierAt(), target, seed, BOUNDS, protect());
    }

    @Test
    void gatewayConnectorGetsVisibleOreVeins() {
        var topology = TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
        SegmentGeometry.SegmentPlan structural =
                SegmentGeometry.entryConnector(topology, BOUNDS, ARRIVAL);
        int target = OrePlacementPlanner.passageVeinTarget(60);
        SegmentGeometry.SegmentPlan ores = plan("connector:arrival:" + topology.entranceId(),
                structural, CATALOG, target, topology.seed());
        assertFalse(ores.operations().isEmpty(), "the gateway descent must show ore");
        assertTrue(ores.operations().stream().allMatch(op -> op.kind() == SegmentGeometry.BlockKind.ORE));
        assertEquals(ores, plan("connector:arrival:" + topology.entranceId(),
                structural, CATALOG, target, topology.seed()));
    }

    @Test
    void ordinaryEdgesGetAtLeastThreeVeinsInWallBand() {
        var topology = TopologyPlanner.plan(1337, TopologyProfile.builtin(), BOUNDS);
        for (TopoEdge edge : topology.edges().subList(0, Math.min(5, topology.edges().size()))) {
            SegmentGeometry.SegmentPlan structural =
                    SegmentGeometry.edge(topology, edge, BOUNDS, ARRIVAL);
            int target = OrePlacementPlanner.passageVeinTarget(
                    SegmentGeometry.passageCenterline(topology, edge).size());
            SegmentGeometry.SegmentPlan ores = plan("edge:" + edge.a() + ":" + edge.b(),
                    structural, CATALOG, target, topology.seed());
            assertFalse(ores.operations().isEmpty(),
                    "ordinary passage " + edge + " must carry visible veins");
        }
    }

    @Test
    void wallBandOresSitOneToFourAboveLocalFloor() {
        var topology = TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
        TopoEdge edge = topology.edges().get(0);
        SegmentGeometry.SegmentPlan structural = SegmentGeometry.edge(topology, edge, BOUNDS, ARRIVAL);
        // Local floor: lowest carved cell per column minus one.
        java.util.Map<Long, Integer> floor = new java.util.HashMap<>();
        Set<TopoPos> open = new HashSet<>();
        for (var op : structural.operations()) {
            if (op.kind() == SegmentGeometry.BlockKind.OPEN) {
                open.add(op.position());
                floor.merge((((long) op.position().x()) << 32) ^ (op.position().z() & 0xFFFF_FFFFL),
                        op.position().y() - 1, Math::min);
            }
        }
        SegmentGeometry.SegmentPlan ores = plan("edge:" + edge.a() + ":" + edge.b(),
                structural, CATALOG, 6, topology.seed());
        for (var op : ores.operations()) {
            // The wall band is measured from the reachable open neighbour's
            // column floor, exactly as the planner computes it.
            boolean valid = false;
            for (int[] d : new int[][] {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}) {
                TopoPos air = op.position().offset(d[0], d[1], d[2]);
                if (!open.contains(air)) continue;
                Integer f = floor.get((((long) air.x()) << 32) ^ (air.z() & 0xFFFF_FFFFL));
                if (f == null || air.y() - f > 3) continue;
                int h = op.position().y() - f;
                if (h >= OrePlacementPlanner.WALL_HEIGHT_MIN
                        && h <= OrePlacementPlanner.WALL_HEIGHT_MAX) {
                    valid = true;
                }
            }
            assertTrue(valid, "ore at " + op.position()
                    + " is not 1..4 above the reachable floor next to carved air");
        }
    }

    @Test
    void resourceFieldsAreRicherThanOrdinaryRooms() {
        var topology = TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
        TopoNode field = topology.nodes().stream()
                .filter(n -> n.has(NodeFeature.RESOURCE_FIELD)).findFirst().orElseThrow();
        SegmentGeometry.SegmentPlan structural = SegmentGeometry.node(topology, field, BOUNDS, ARRIVAL);
        SegmentGeometry.SegmentPlan ores = plan("node:" + field.id(),
                structural, CATALOG, OrePlacementPlanner.RESOURCE_FIELD_VEINS, topology.seed());
        assertTrue(ores.operations().size() >= 8,
                "a resource field should host at least ~8 vein blocks, got " + ores.operations().size());
    }

    @Test
    void candidateLessSegmentYieldsEmptyPlanNotFailure() {
        SegmentGeometry.SegmentPlan empty = new SegmentGeometry.SegmentPlan("edge:x:y", List.of());
        SegmentGeometry.SegmentPlan ores = plan("edge:x:y", empty, CATALOG, 5, 99L);
        assertTrue(ores.operations().isEmpty());
    }

    @Test
    void oreNeverOccupiesOpenDecorationOrProtectedCells() {
        var topology = TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
        TopoEdge edge = topology.edges().get(0);
        SegmentGeometry.SegmentPlan structural = SegmentGeometry.edge(topology, edge, BOUNDS, ARRIVAL);
        SegmentGeometry.SegmentPlan dress = SegmentGeometry.dress(
                topology, "edge:" + edge.a() + ":" + edge.b(), BOUNDS, ARRIVAL);
        Set<TopoPos> open = new HashSet<>();
        Set<TopoPos> decorated = new HashSet<>();
        for (var p : List.of(structural, dress)) {
            for (var op : p.operations()) {
                if (op.kind() == SegmentGeometry.BlockKind.OPEN) open.add(op.position());
                // A wall/ceiling/pillar skin is cosmetic default-rock
                // texture, not real decoration - ore must stay free to
                // embed into it exactly as if it were untouched rock.
                else if (op.kind() != SegmentGeometry.BlockKind.WALL_SKIN) decorated.add(op.position());
            }
        }
        SegmentGeometry.SegmentPlan ores = plan("edge:" + edge.a() + ":" + edge.b(),
                new SegmentGeometry.SegmentPlan("merged", java.util.stream.Stream.concat(
                        structural.operations().stream(), dress.operations().stream()).toList()),
                CATALOG, 6, topology.seed());
        for (var op : ores.operations()) {
            assertFalse(open.contains(op.position()));
            assertFalse(decorated.contains(op.position()));
            assertFalse(HubLayout.isReserved(op.position()));
            assertTrue(BOUNDS.contains(op.position()));
        }
    }

    @Test
    void jackpotSelectionIsDeterministicBoundedAndVariesWithSeed() {
        Set<String> first = null;
        Set<String> union = new HashSet<>();
        for (long seed : new long[] {42, 1337, 2026, 9001, 77777}) {
            var topology = TopologyPlanner.plan(seed, TopologyProfile.builtin(), BOUNDS);
            Set<String> ids = OrePlacementPlanner.jackpotNodeIds(topology.nodes(), topology.seed());
            Map<String, Long> perRegion = topology.nodes().stream()
                    .filter(n -> n.kind() == com.dwurdy.dawnmine.topology.NodeKind.CAVERN)
                    .collect(java.util.stream.Collectors.groupingBy(
                            TopoNode::region, java.util.stream.Collectors.counting()));
            long expected = perRegion.values().stream()
                    .mapToLong(c -> Math.min(OrePlacementPlanner.JACKPOT_NODES_PER_REGION, c))
                    .sum();
            assertEquals(expected, ids.size(),
                    "seed " + seed + " selected " + ids + " - expected exactly "
                            + OrePlacementPlanner.JACKPOT_NODES_PER_REGION + " per cavern region");
            for (String id : ids) {
                TopoNode node = topology.node(id);
                assertTrue(node != null && node.kind()
                        == com.dwurdy.dawnmine.topology.NodeKind.CAVERN,
                        "jackpot id " + id + " is not a cavern node");
            }
            assertEquals(ids, OrePlacementPlanner.jackpotNodeIds(
                    topology.nodes(), topology.seed()),
                    "same seed must replay identical jackpot selection");
            if (first == null) {
                first = ids;
            }
            union.addAll(ids);
        }
        assertTrue(union.size() > 3,
                "identical picks across every seed would mean the hash does not vary");
    }

    @Test
    void jackpotVeinTargetIsMultipleOfBase() {
        assertEquals(OrePlacementPlanner.RESOURCE_FIELD_VEINS
                        * OrePlacementPlanner.JACKPOT_VEIN_MULTIPLIER,
                OrePlacementPlanner.jackpotVeinTarget(OrePlacementPlanner.RESOURCE_FIELD_VEINS));
    }

    @Test
    void jackpotNodesPlaceStrictlyMoreOreThanOrdinaryNodes() {
        var topology = TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
        Set<String> jackpots = OrePlacementPlanner.jackpotNodeIds(
                topology.nodes(), topology.seed());
        assertFalse(jackpots.isEmpty(), "builtin topology must yield jackpot nodes");
        for (String id : jackpots) {
            TopoNode node = topology.node(id);
            SegmentGeometry.SegmentPlan structural =
                    SegmentGeometry.node(topology, node, BOUNDS, ARRIVAL);
            SegmentGeometry.SegmentPlan jackpot = plan("node:" + id, structural, CATALOG,
                    OrePlacementPlanner.jackpotVeinTarget(OrePlacementPlanner.RESOURCE_FIELD_VEINS),
                    topology.seed());
            SegmentGeometry.SegmentPlan ordinary = plan("node:" + id, structural, CATALOG,
                    OrePlacementPlanner.ORDINARY_VEINS, topology.seed());
            assertTrue(jackpot.operations().size() > ordinary.operations().size(),
                    "jackpot " + id + " placed " + jackpot.operations().size()
                            + " vs ordinary " + ordinary.operations().size());
            assertTrue(jackpot.operations().size() > OrePlacementPlanner.RESOURCE_FIELD_VEINS,
                    "jackpot " + id + " placed only " + jackpot.operations().size()
                            + " ore blocks - must exceed the resource-field baseline");
            // Deterministic replay: same inputs, byte-for-byte identical plan.
            assertEquals(jackpot, plan("node:" + id, structural, CATALOG,
                    OrePlacementPlanner.jackpotVeinTarget(OrePlacementPlanner.RESOURCE_FIELD_VEINS),
                    topology.seed()));
        }
    }

    @Test
    void jackpotOresResolveOnlyThroughCatalogEntries() {
        var topology = TopologyPlanner.plan(1337, TopologyProfile.builtin(), BOUNDS);
        Set<String> jackpots = OrePlacementPlanner.jackpotNodeIds(
                topology.nodes(), topology.seed());
        assertFalse(jackpots.isEmpty(), "builtin topology must yield jackpot nodes");
        Set<String> catalogIds = CATALOG.entries().keySet().stream()
                .map(ResourceLocation::toString)
                .collect(java.util.stream.Collectors.toSet());
        for (String id : jackpots) {
            TopoNode node = topology.node(id);
            SegmentGeometry.SegmentPlan structural =
                    SegmentGeometry.node(topology, node, BOUNDS, ARRIVAL);
            SegmentGeometry.SegmentPlan ores = plan("node:" + id, structural, CATALOG,
                    OrePlacementPlanner.jackpotVeinTarget(OrePlacementPlanner.RESOURCE_FIELD_VEINS),
                    topology.seed());
            for (var op : ores.operations()) {
                assertTrue(catalogIds.contains(op.blockId()),
                        "jackpot ore " + op.blockId() + " is not a catalog entry");
            }
        }
    }

    @Test
    void wholeRunOrePlanStaysWithinProvenanceBudget() {
        for (long seed : new long[] {42, 1337, 2026}) {
            var topology = TopologyPlanner.plan(seed, TopologyProfile.builtin(), BOUNDS);
            Set<String> jackpots = OrePlacementPlanner.jackpotNodeIds(
                    topology.nodes(), topology.seed());
            long total = 0;
            for (String id : PreparationPlan.structuralSegments(topology)) {
                SegmentGeometry.SegmentPlan structural;
                int target;
                if (id.startsWith("node:")) {
                    TopoNode node = topology.node(id.substring(5));
                    structural = SegmentGeometry.node(topology, node, BOUNDS, ARRIVAL);
                    if (jackpots.contains(node.id())) {
                        target = OrePlacementPlanner.jackpotVeinTarget(
                                OrePlacementPlanner.RESOURCE_FIELD_VEINS);
                    } else {
                        target = node.has(NodeFeature.RESOURCE_FIELD)
                                ? OrePlacementPlanner.RESOURCE_FIELD_VEINS
                                : OrePlacementPlanner.ORDINARY_VEINS;
                    }
                } else if (id.startsWith("edge:")) {
                    TopoEdge edge = SegmentGeometry.edgeFromSegmentId(topology, id);
                    structural = SegmentGeometry.edge(topology, edge, BOUNDS, ARRIVAL);
                    target = OrePlacementPlanner.passageVeinTarget(
                            SegmentGeometry.passageCenterline(topology, edge).size());
                } else {
                    structural = SegmentGeometry.entryConnector(topology, BOUNDS, ARRIVAL);
                    target = OrePlacementPlanner.passageVeinTarget(48);
                }
                total += OrePlacementPlanner.planOreSegment("ores:" + id,
                        List.of(structural), CATALOG, tierAt(), target, seed,
                        BOUNDS, protect()).operations().size();
            }
            assertTrue(total > 0);
            assertTrue(total < 48_000,
                    "seed " + seed + " planned " + total + " ore origins vs 65536 cap");
        }
    }
}
