package com.dwurdy.dawnmine.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.dawnmine.generation.MotifCatalog.BlockSlot;
import com.dwurdy.dawnmine.generation.MotifCatalog.Element;
import com.dwurdy.dawnmine.generation.MotifCatalog.ElementType;
import com.dwurdy.dawnmine.generation.MotifCatalog.Motif;
import com.dwurdy.dawnmine.generation.MotifCatalog.OffsetRule;
import com.dwurdy.dawnmine.generation.MotifCatalog.Range;
import com.dwurdy.dawnmine.topology.HubLayout;
import com.dwurdy.dawnmine.topology.TopoBounds;
import com.dwurdy.dawnmine.topology.TopoEdge;
import com.dwurdy.dawnmine.topology.TopoPos;
import com.dwurdy.dawnmine.topology.TopologyPlanner;
import com.dwurdy.dawnmine.topology.TopologyProfile;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class MotifPlannerTest {
    private static final TopoBounds BOUNDS = new TopoBounds(-1024, -64, -1024, 1023, 319, 1023);
    private static final TopoPos ARRIVAL = new TopoPos(0, 42, 8);
    private static final OreCatalog.OreCatalogSnapshot ORES = OreCatalog.OreCatalogSnapshot.fromEntries(List.of(
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

    private static Motif rubbleMotif() {
        return new Motif("test_rubble", Set.of("edge"), Set.of("upper", "middle", "chaos"),
                new Range(6, 10), 1.0, List.of(
                new Element(ElementType.SCATTER, BlockSlot.RUBBLE, new Range(2, 5),
                        OffsetRule.FLOOR, 2, 1.0),
                new Element(ElementType.PILLAR, BlockSlot.SUPPORT, new Range(1, 3),
                        OffsetRule.FLOOR, 3, 0.8),
                new Element(ElementType.LAMP, BlockSlot.LIGHT, new Range(1, 1),
                        OffsetRule.FLOOR, 3, 0.6)));
    }

    private static Motif veinMotif() {
        return new Motif("test_vein", Set.of("edge"), Set.of("upper", "middle", "chaos"),
                new Range(6, 10), 1.0, List.of(
                new Element(ElementType.VEIN, BlockSlot.ORE_SLOT, new Range(2, 4),
                        OffsetRule.WALL, 3, 1.0)));
    }

    private static SegmentGeometry.SegmentPlan motifPlan(String structuralId,
            List<SegmentGeometry.SegmentPlan> plans, List<Motif> motifs,
            Set<String> tags, List<TopoPos> spine, long seed,
            Set<TopoPos> clearance) {
        return MotifPlanner.planMotifSegment("motif:" + structuralId, plans, motifs,
                tags, spine, true, ORES, tierAt(), seed, BOUNDS, clearance,
                HubLayout::isReserved);
    }

    @Test
    void sameSeedReplaysIdentically() {
        var topology = TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
        TopoEdge edge = topology.edges().get(0);
        String id = SegmentGeometry.edgeSegmentId(edge);
        List<SegmentGeometry.SegmentPlan> plans = List.of(
                SegmentGeometry.edge(topology, edge, BOUNDS, ARRIVAL),
                SegmentGeometry.dress(topology, id, BOUNDS, ARRIVAL));
        List<TopoPos> spine = SegmentGeometry.passageCenterline(topology, edge);
        Set<TopoPos> clearance = SegmentGeometry.wholeRunClearance(topology, ARRIVAL);
        List<Motif> motifs = List.of(rubbleMotif(), veinMotif());
        Set<String> tags = Set.of("edge", "main", "mineshaft");
        SegmentGeometry.SegmentPlan first = motifPlan(id, plans, motifs, tags, spine,
                topology.seed(), clearance);
        SegmentGeometry.SegmentPlan second = motifPlan(id, plans, motifs, tags, spine,
                topology.seed(), clearance);
        assertEquals(first, second, "same seed must replay byte-identical motif ops");
    }

    @Test
    void differentSeedsProduceDifferentLayouts() {
        var topology = TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
        TopoEdge edge = topology.edges().get(0);
        String id = SegmentGeometry.edgeSegmentId(edge);
        List<SegmentGeometry.SegmentPlan> plans = List.of(
                SegmentGeometry.edge(topology, edge, BOUNDS, ARRIVAL),
                SegmentGeometry.dress(topology, id, BOUNDS, ARRIVAL));
        List<TopoPos> spine = SegmentGeometry.passageCenterline(topology, edge);
        Set<TopoPos> clearance = SegmentGeometry.wholeRunClearance(topology, ARRIVAL);
        List<Motif> motifs = List.of(rubbleMotif(), veinMotif());
        Set<String> tags = Set.of("edge", "main", "mineshaft");
        SegmentGeometry.SegmentPlan one = motifPlan(id, plans, motifs, tags, spine, 1L, clearance);
        SegmentGeometry.SegmentPlan two = motifPlan(id, plans, motifs, tags, spine, 2L, clearance);
        assertNotEquals(one.operations(), two.operations(),
                "different run seeds must re-roll motif placements");
    }

    @Test
    void motifOpsNeverOccupyWalkClearance() {
        var topology = TopologyPlanner.plan(1337, TopologyProfile.builtin(), BOUNDS);
        Set<TopoPos> clearance = SegmentGeometry.wholeRunClearance(topology, ARRIVAL);
        List<Motif> motifs = List.of(rubbleMotif(), veinMotif());
        int checked = 0;
        for (TopoEdge edge : topology.edges().subList(0, Math.min(8, topology.edges().size()))) {
            String id = SegmentGeometry.edgeSegmentId(edge);
            List<SegmentGeometry.SegmentPlan> plans = List.of(
                    SegmentGeometry.edge(topology, edge, BOUNDS, ARRIVAL),
                    SegmentGeometry.dress(topology, id, BOUNDS, ARRIVAL));
            List<TopoPos> spine = SegmentGeometry.passageCenterline(topology, edge);
            Set<String> tags = Set.of("edge", "main", "mineshaft", "natural");
            SegmentGeometry.SegmentPlan motif = motifPlan(id, plans, motifs, tags, spine,
                    topology.seed(), clearance);
            for (SegmentGeometry.BlockOperation op : motif.operations()) {
                assertTrue(!clearance.contains(op.position())
                                || op.kind() == SegmentGeometry.BlockKind.RAIL,
                        "motif op " + op + " must never block a shared walk cell");
                checked++;
            }
        }
        assertTrue(checked > 0, "fixture must place at least some motif ops");
    }

    @Test
    void oreSlotResolvesThroughCatalogOnly() {
        var topology = TopologyPlanner.plan(1337, TopologyProfile.builtin(), BOUNDS);
        Set<TopoPos> clearance = SegmentGeometry.wholeRunClearance(topology, ARRIVAL);
        Set<String> catalogIds = Set.of("minecraft:coal_ore", "minecraft:gold_ore",
                "minecraft:diamond_ore");
        int oreOps = 0;
        for (TopoEdge edge : topology.edges().subList(0, Math.min(8, topology.edges().size()))) {
            String id = SegmentGeometry.edgeSegmentId(edge);
            List<SegmentGeometry.SegmentPlan> plans = List.of(
                    SegmentGeometry.edge(topology, edge, BOUNDS, ARRIVAL),
                    SegmentGeometry.dress(topology, id, BOUNDS, ARRIVAL));
            List<TopoPos> spine = SegmentGeometry.passageCenterline(topology, edge);
            SegmentGeometry.SegmentPlan motif = motifPlan(id, plans, List.of(veinMotif()),
                    Set.of("edge", "main"), spine, topology.seed(), clearance);
            for (SegmentGeometry.BlockOperation op : motif.operations()) {
                assertEquals(SegmentGeometry.BlockKind.ORE, op.kind());
                assertTrue(catalogIds.contains(op.blockId()),
                        "ore_slot resolved to " + op.blockId() + " outside the catalog");
                oreOps++;
            }
        }
        assertTrue(oreOps > 0, "vein motif must embed catalog ore in walls");
    }

    @Test
    void foreignSegmentReceivesNothing() {
        var topology = TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
        TopoEdge edge = topology.edges().get(0);
        String id = SegmentGeometry.edgeSegmentId(edge);
        List<SegmentGeometry.SegmentPlan> plans = List.of(
                SegmentGeometry.edge(topology, edge, BOUNDS, ARRIVAL));
        List<TopoPos> spine = SegmentGeometry.passageCenterline(topology, edge);
        Motif cavernOnly = new Motif("cavern_only", Set.of("cavern"),
                Set.of("upper", "middle", "chaos"), new Range(4, 8), 1.0, List.of(
                new Element(ElementType.SCATTER, BlockSlot.RUBBLE, new Range(2, 4),
                        OffsetRule.FLOOR, 2, 1.0)));
        SegmentGeometry.SegmentPlan motif = motifPlan(id, plans, List.of(cavernOnly),
                Set.of("edge", "main", "natural"), spine, topology.seed(),
                SegmentGeometry.wholeRunClearance(topology, ARRIVAL));
        assertTrue(motif.operations().isEmpty(),
                "a cavern-only motif must not place on an edge segment");
    }

    @Test
    void emptyCatalogYieldsEmptyPlan() {
        var topology = TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
        TopoEdge edge = topology.edges().get(0);
        String id = SegmentGeometry.edgeSegmentId(edge);
        List<SegmentGeometry.SegmentPlan> plans = List.of(
                SegmentGeometry.edge(topology, edge, BOUNDS, ARRIVAL));
        SegmentGeometry.SegmentPlan motif = motifPlan(id, plans, List.of(),
                Set.of("edge"), SegmentGeometry.passageCenterline(topology, edge),
                topology.seed(), Set.of());
        assertTrue(motif.operations().isEmpty());
        assertEquals("motif:" + id, motif.id());
    }
}
