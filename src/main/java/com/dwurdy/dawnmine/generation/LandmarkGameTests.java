package com.dwurdy.dawnmine.generation;

import com.dwurdy.dawnmine.run.MineManager;
import com.dwurdy.dawnmine.topology.MineTopology;
import com.dwurdy.dawnmine.topology.NodeFeature;
import com.dwurdy.dawnmine.topology.TopoEdge;
import com.dwurdy.dawnmine.topology.TopoNode;
import com.dwurdy.dawnmine.topology.TopoPos;
import com.dwurdy.dawnmine.topology.TopologyPlan;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Prepared-run proofs for the static landmark safehouse contract: the
 * reserve is impenetrable to foreign writes, edges terminate exactly at
 * declared connector cells, and a landmark segment replays identically
 * after a mid-generation restart. */
@GameTestHolder("dawnmine")
@PrefixGameTestTemplate(false)
public final class LandmarkGameTests {
    private static final TopoPos ARRIVAL = new TopoPos(
            MineManager.ARRIVAL.getX(), MineManager.ARRIVAL.getY(), MineManager.ARRIVAL.getZ());

    private LandmarkGameTests() { }

    @GameTest(template = "empty", timeoutTicks = 1200, batch = "dawnmine_hub_prepared")
    public static void safehouseEdgesTerminateAtDeclaredConnectors(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        helper.assertTrue(MineManager.get(server).preparationComplete(),
                "fixture requires a prepared mine");
        ServerLevel mine = server.getLevel(MineManager.MINE_DIMENSION);
        helper.assertTrue(mine != null, "mine dimension not loaded");
        TopologyPlan topology = MineTopology.plan(MineManager.get(server).data(), mine);
        PassageNetwork network = PassageNetwork.of(topology, ARRIVAL);
        LandmarkLayout layout = network.landmarks();
        helper.assertTrue(!layout.placements().isEmpty(),
                "prepared topology placed no landmarks");

        int landmarkEdges = 0;
        for (TopoEdge edge : topology.edges()) {
            boolean aLandmark = layout.placement(edge.a()) != null;
            boolean bLandmark = layout.placement(edge.b()) != null;
            if (!aLandmark && !bLandmark) {
                continue;
            }
            landmarkEdges++;
            List<TopoPos> path = network.path(SegmentGeometry.edgeSegmentId(edge));
            if (aLandmark) {
                helper.assertTrue(layout.connectors(edge.a()).contains(path.get(0)),
                        "edge " + edge.a() + "<->" + edge.b() + " starts at " + path.get(0)
                                + " which is not a declared connector of " + edge.a());
            }
            if (bLandmark) {
                helper.assertTrue(layout.connectors(edge.b())
                                .contains(path.get(path.size() - 1)),
                        "edge " + edge.a() + "<->" + edge.b() + " ends at "
                                + path.get(path.size() - 1)
                                + " which is not a declared connector of " + edge.b());
            }
        }
        helper.assertTrue(landmarkEdges > 0,
                "no edges touch a safehouse - placement is unreachable");
        // The safehouse contract: every declared doorway is physically
        // open with its approach tunnel and interior carved. Direction is
        // derived from the declared facing so defs with other door walls
        // stay valid fixtures.
        for (LandmarkLayout.Placement p : layout.placements().values()) {
            List<LandmarkCatalog.Connector> connectors = p.definition().connectors();
            helper.assertTrue(!connectors.isEmpty(),
                    "safehouse " + p.definitionId() + " at " + p.nodeId()
                            + " declares no connectors - unreachable room");
            for (TopoPos door : layout.connectors(p.nodeId())) {
                helper.assertTrue(mine.getBlockState(
                                new BlockPos(door.x(), door.y(), door.z())).isAir(),
                        "connector cell " + door + " is not open in the world");
                helper.assertTrue(mine.getBlockState(
                                new BlockPos(door.x(), door.y() + 1, door.z())).isAir(),
                        "connector headroom " + door + " is not open in the world");
                int[] dir = approachDirection(p.definition(), door, p.anchor());
                TopoPos approach = door.offset(dir[0], 0, dir[1]);
                helper.assertTrue(mine.getBlockState(
                                new BlockPos(approach.x(), approach.y(), approach.z()))
                                .isAir(),
                        "connector approach " + approach + " is blocked in the world");
            }
            TopoPos centre = new TopoPos(p.anchor().x(), p.anchor().y() + 1,
                    p.anchor().z());
            helper.assertTrue(mine.getBlockState(
                            new BlockPos(centre.x(), centre.y(), centre.z())).isAir(),
                    "safehouse " + p.nodeId() + " interior centre is sealed");
        }
        helper.succeed();
    }

    /** The outward XZ step from a door cell toward its approach tunnel,
     * derived from the connector's declared facing. */
    private static int[] approachDirection(LandmarkCatalog.Definition def,
                                           TopoPos door, TopoPos anchor) {
        for (LandmarkCatalog.Connector c : def.connectors()) {
            if (anchor.offset(c.x(), c.y(), c.z()).equals(door)) {
                return switch (c.facing()) {
                    case "east" -> new int[] {1, 0};
                    case "west" -> new int[] {-1, 0};
                    case "south" -> new int[] {0, 1};
                    default -> new int[] {0, -1};
                };
            }
        }
        return new int[] {0, 0};
    }

    @GameTest(template = "empty", timeoutTicks = 1200, batch = "dawnmine_hub_prepared")
    public static void safehouseReserveBlocksForeignWrites(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        helper.assertTrue(MineManager.get(server).preparationComplete(),
                "fixture requires a prepared mine");
        ServerLevel mine = server.getLevel(MineManager.MINE_DIMENSION);
        helper.assertTrue(mine != null, "mine dimension not loaded");
        TopologyPlan topology = MineTopology.plan(MineManager.get(server).data(), mine);
        PassageNetwork network = PassageNetwork.of(topology, ARRIVAL);
        LandmarkLayout layout = network.landmarks();
        helper.assertTrue(!layout.placements().isEmpty(),
                "prepared topology placed no landmarks - reserve assertions would be vacuous");

        // Replay every non-landmark structural/dress plan and assert no
        // operation lands inside the write-protected reserve.
        for (String segmentId : PreparationPlan.structuralSegments(topology)) {
            if (segmentId.startsWith(StaticLandmarkBuilder.SEGMENT_PREFIX)) {
                continue;
            }
            SegmentGeometry.SegmentPlan plan = MineGenerator.createPlanForTest(
                    topology, segmentId, ARRIVAL);
            if (plan == null) {
                continue;
            }
            for (var op : plan.operations()) {
                helper.assertTrue(!layout.isWriteProtected(op.position()),
                        "segment " + segmentId + " writes inside a landmark "
                                + "reserve at " + op.position());
            }
            for (var op : SegmentGeometry.dress(topology, segmentId,
                    topology.bounds(), ARRIVAL).operations()) {
                helper.assertTrue(!layout.isWriteProtected(op.position()),
                        "dress segment " + segmentId + " writes inside a "
                                + "landmark reserve at " + op.position());
            }
        }
        // Ore writes are the largest foreign-write surface: replay ore
        // plans for nodes whose rooms can reach a reserve and assert the
        // production keepout holds there too.
        for (TopoNode node : topology.nodes()) {
            if (node.kind() != com.dwurdy.dawnmine.topology.NodeKind.CAVERN) {
                continue;
            }
            boolean nearReserve = layout.placements().values().stream()
                    .anyMatch(p -> Math.abs(node.pos().x() - p.anchor().x()) <= 64
                            && Math.abs(node.pos().z() - p.anchor().z()) <= 64);
            if (!nearReserve) {
                continue;
            }
            String segId = "node:" + node.id();
            SegmentGeometry.SegmentPlan structural = SegmentGeometry.node(
                    topology, node, topology.bounds(), ARRIVAL);
            java.util.List<SegmentGeometry.SegmentPlan> plans = java.util.List.of(
                    structural, SegmentGeometry.dress(topology, segId,
                            topology.bounds(), ARRIVAL));
            SegmentGeometry.SegmentPlan ores = OrePlacementPlanner.planOreSegment(
                    "ores:" + segId, plans, OreCatalog.active(),
                    pos -> OreCatalog.Tier.parse(regionForY(pos.y())),
                    OrePlacementPlanner.ORDINARY_VEINS, topology.seed(),
                    topology.bounds(),
                    pos -> SegmentGeometry.isArrivalKeepOut(pos, ARRIVAL)
                            || com.dwurdy.dawnmine.topology.HubLayout.isReserved(pos)
                            || layout.isReserved(pos));
            for (var op : ores.operations()) {
                helper.assertTrue(!layout.isReserved(op.position()),
                        "ore plan " + segId + " writes inside a landmark "
                                + "reserve at " + op.position());
            }
        }
        helper.succeed();
    }

    private static String regionForY(int y) {
        if (y >= -40) {
            return "upper";
        }
        return y >= -80 ? "middle" : "chaos";
    }

    @GameTest(template = "empty", timeoutTicks = 1200, batch = "dawnmine_hub_prepared")
    public static void safehouseSegmentsReplayIdentically(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        helper.assertTrue(MineManager.get(server).preparationComplete(),
                "fixture requires a prepared mine");
        ServerLevel mine = server.getLevel(MineManager.MINE_DIMENSION);
        helper.assertTrue(mine != null, "mine dimension not loaded");
        TopologyPlan topology = MineTopology.plan(MineManager.get(server).data(), mine);
        PassageNetwork network = PassageNetwork.of(topology, ARRIVAL);
        LandmarkLayout layout = network.landmarks();

        int safehouses = 0;
        for (TopoNode node : topology.nodes()) {
            if (!node.has(NodeFeature.SAFEHOUSE)) {
                continue;
            }
            safehouses++;
            LandmarkLayout.Placement placement = layout.placement(node.id());
            helper.assertTrue(placement != null,
                    "safehouse node " + node.id() + " has no placement");
            // Restart-mid-generation safety: the plan is a pure function
            // of definition + anchor, so it must rebuild identically.
            SegmentGeometry.SegmentPlan first = StaticLandmarkBuilder.plan(
                    placement, topology.bounds());
            SegmentGeometry.SegmentPlan second = StaticLandmarkBuilder.plan(
                    placement, topology.bounds());
            helper.assertTrue(first.operations().equals(second.operations()),
                    "landmark segment " + first.id() + " is not replay-stable");
            // Interior air slots must emit OPEN ops - a partial replay can
            // never leave the interior sealed.
            helper.assertTrue(first.operations().stream().anyMatch(
                    o -> o.kind() == SegmentGeometry.BlockKind.OPEN),
                    "landmark segment " + first.id() + " carves no interior");
        }
        helper.assertTrue(safehouses > 0,
                "prepared topology marked no safehouse nodes");
        helper.succeed();
    }
}
