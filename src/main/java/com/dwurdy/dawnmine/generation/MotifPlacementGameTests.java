package com.dwurdy.dawnmine.generation;

import com.dwurdy.dawnmine.gametest.PreparedFixture;
import com.dwurdy.dawnmine.run.MineManager;
import com.dwurdy.dawnmine.topology.MineTopology;
import com.dwurdy.dawnmine.topology.TopoPos;
import com.dwurdy.dawnmine.topology.TopologyPlan;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Live proof for the procedural motif pass on a prepared run:
 * placements replay deterministically, never occupy a shared walk cell,
 * resolve ore slots only through the active catalog, and never float. */
@GameTestHolder("dawnmine")
@PrefixGameTestTemplate(false)
public final class MotifPlacementGameTests {
    private static final TopoPos ARRIVAL = new TopoPos(0, 42, 8);
    private static final int SAMPLE = 12;

    private MotifPlacementGameTests() { }

    private static TopologyPlan preparedTopology(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ServerLevel mine = server.getLevel(MineManager.MINE_DIMENSION);
        helper.assertTrue(mine != null, "mine dimension not loaded");
        return MineTopology.plan(MineManager.get(server).data(), mine);
    }

    private static List<String> sampleStructuralIds(TopologyPlan topology) {
        List<String> ids = PreparationPlan.structuralSegments(topology);
        return ids.subList(0, Math.min(SAMPLE, ids.size()));
    }

    @GameTest(template = "empty", timeoutTicks = 6000, batch = "dawnmine_hub_prepared")
    public static void motifPlacementReplaysIdentically(GameTestHelper helper) {
        PreparedFixture.awaitPrepared(helper,
                MotifPlacementGameTests::runMotifPlacementReplaysIdentically);
    }

    private static void runMotifPlacementReplaysIdentically(GameTestHelper helper) {
        TopologyPlan topology = preparedTopology(helper);
        int compared = 0;
        for (String id : sampleStructuralIds(topology)) {
            SegmentGeometry.SegmentPlan first = MotifPlanner.planSegment(topology, id, ARRIVAL);
            SegmentGeometry.SegmentPlan second = MotifPlanner.planSegment(topology, id, ARRIVAL);
            helper.assertTrue(first.equals(second),
                    "motif segment motif:" + id + " is not replay-deterministic");
            compared++;
        }
        helper.assertTrue(compared > 0, "no motif segments sampled");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 6000, batch = "dawnmine_hub_prepared")
    public static void motifOpsNeverBlockWalkCells(GameTestHelper helper) {
        PreparedFixture.awaitPrepared(helper,
                MotifPlacementGameTests::runMotifOpsNeverBlockWalkCells);
    }

    private static void runMotifOpsNeverBlockWalkCells(GameTestHelper helper) {
        TopologyPlan topology = preparedTopology(helper);
        Set<TopoPos> clearance = SegmentGeometry.wholeRunClearance(topology, ARRIVAL);
        int ops = 0;
        for (String id : sampleStructuralIds(topology)) {
            SegmentGeometry.SegmentPlan motif = MotifPlanner.planSegment(topology, id, ARRIVAL);
            for (SegmentGeometry.BlockOperation op : motif.operations()) {
                helper.assertTrue(!clearance.contains(op.position())
                                || op.kind() == SegmentGeometry.BlockKind.RAIL,
                        "motif op " + op + " blocks a shared walk cell in motif:" + id);
                ops++;
            }
        }
        helper.assertTrue(ops > 0,
                "sampled segments produced no motif ops - the pass is vacuous");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 6000, batch = "dawnmine_hub_prepared")
    public static void motifOreSlotsAndSupportsStayLegal(GameTestHelper helper) {
        PreparedFixture.awaitPrepared(helper,
                MotifPlacementGameTests::runMotifOreSlotsAndSupportsStayLegal);
    }

    private static void runMotifOreSlotsAndSupportsStayLegal(GameTestHelper helper) {
        TopologyPlan topology = preparedTopology(helper);
        OreCatalog.OreCatalogSnapshot catalog = OreCatalog.active();
        int oreOps = 0;
        int checked = 0;
        for (String id : sampleStructuralIds(topology)) {
            SegmentGeometry.SegmentPlan motif = MotifPlanner.planSegment(topology, id, ARRIVAL);
            // Every solid motif op placed into carved air must stand on
            // something: a claimed dress cell, natural rock, or an
            // earlier motif op - nothing may float.
            Set<TopoPos> open = new HashSet<>();
            Set<TopoPos> claimed = new HashSet<>();
            for (SegmentGeometry.BlockOperation op : MineGenerator
                    .createPlanForTest(topology, id, ARRIVAL).operations()) {
                (op.kind() == SegmentGeometry.BlockKind.OPEN ? open : claimed)
                        .add(op.position());
            }
            for (SegmentGeometry.BlockOperation op : SegmentGeometry
                    .dress(topology, id, topology.bounds(), ARRIVAL).operations()) {
                (op.kind() == SegmentGeometry.BlockKind.OPEN ? open : claimed)
                        .add(op.position());
            }
            Set<TopoPos> motifSolids = new HashSet<>();
            for (SegmentGeometry.BlockOperation op : motif.operations()) {
                if (op.kind() != SegmentGeometry.BlockKind.RAIL) {
                    motifSolids.add(op.position());
                }
                if (op.kind() == SegmentGeometry.BlockKind.ORE) {
                    helper.assertTrue(catalog.entry(
                                    net.minecraft.resources.ResourceLocation.parse(op.blockId()))
                                    .isPresent(),
                            "ore_slot resolved to uncatalogued " + op.blockId());
                    oreOps++;
                }
            }
            for (SegmentGeometry.BlockOperation op : motif.operations()) {
                if (op.kind() == SegmentGeometry.BlockKind.RAIL
                        || op.kind() == SegmentGeometry.BlockKind.ORE
                        || !open.contains(op.position())) {
                    continue; // embedded or walk-through - cannot float
                }
                TopoPos below = op.position().offset(0, -1, 0);
                helper.assertTrue(!open.contains(below) || claimed.contains(below)
                                || motifSolids.contains(below),
                        "motif op " + op + " floats above unsupported air in motif:" + id);
                checked++;
            }
        }
        helper.assertTrue(oreOps > 0 || checked > 0,
                "motif assertions were vacuous");
        helper.succeed();
    }
}
