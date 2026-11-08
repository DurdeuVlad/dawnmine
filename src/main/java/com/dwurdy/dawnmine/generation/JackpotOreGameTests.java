package com.dwurdy.dawnmine.generation;

import com.dwurdy.dawnmine.gametest.PreparedFixture;
import com.dwurdy.dawnmine.run.MineManager;
import com.dwurdy.dawnmine.topology.HubLayout;
import com.dwurdy.dawnmine.topology.MineTopology;
import com.dwurdy.dawnmine.topology.NodeFeature;
import com.dwurdy.dawnmine.topology.NodeKind;
import com.dwurdy.dawnmine.topology.TopoNode;
import com.dwurdy.dawnmine.topology.TopoPos;
import com.dwurdy.dawnmine.topology.TopologyPlan;
import com.dwurdy.dawnmine.topology.TopologyProfiles;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Server-side GameTest coverage for M9b jackpot ore nodes on a prepared run. */
@GameTestHolder("dawnmine")
@PrefixGameTestTemplate(false)
public final class JackpotOreGameTests {
    private static final TopoPos ARRIVAL = new TopoPos(
            MineManager.ARRIVAL.getX(), MineManager.ARRIVAL.getY(), MineManager.ARRIVAL.getZ());

    private JackpotOreGameTests() { }

    @GameTest(template = "empty", timeoutTicks = 6000, batch = "dawnmine_hub_prepared")
    public static void jackpotCavernsCarryElevatedOreCounts(GameTestHelper helper) {
        PreparedFixture.awaitPrepared(helper,
                JackpotOreGameTests::runJackpotCavernsCarryElevatedOreCounts);
    }

    private static void runJackpotCavernsCarryElevatedOreCounts(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ServerLevel mine = server.getLevel(MineManager.MINE_DIMENSION);
        helper.assertTrue(mine != null, "mine dimension not loaded");
        var data = MineManager.get(server).data();
        TopologyPlan topology = MineTopology.plan(data, mine);

        Set<String> jackpotIds = OrePlacementPlanner.jackpotNodeIds(
                topology.nodes(), topology.seed());
        helper.assertTrue(!jackpotIds.isEmpty(),
                "prepared topology selected no jackpot caverns");
        List<TopoNode> caverns = topology.nodes().stream()
                .filter(node -> node.kind() == NodeKind.CAVERN)
                .toList();
        Map<String, Integer> regionsWithCaverns = new HashMap<>();
        for (TopoNode cavern : caverns) {
            regionsWithCaverns.merge(cavern.region(), 1, Integer::sum);
        }
        helper.assertTrue(jackpotIds.size()
                        == regionsWithCaverns.size() * OrePlacementPlanner.JACKPOT_NODES_PER_REGION,
                "jackpot set " + jackpotIds + " does not match the configured "
                        + OrePlacementPlanner.JACKPOT_NODES_PER_REGION
                        + " per cavern region over " + regionsWithCaverns.keySet());

        // Exact attribution: replay each cavern's own ore plan with the same
        // arguments the generator used, then verify the planned blocks exist
        // in the world. No positional guessing between adjacent caverns.
        PassageNetwork network = PassageNetwork.of(
                topology, ARRIVAL, LandmarkCatalog.definitions());
        LandmarkLayout landmarks = network.landmarks();
        Set<TopoPos> protectedCells = checkpointPads(topology, network);
        Map<String, Integer> verifiedCounts = new HashMap<>();
        for (TopoNode cavern : caverns) {
            String segId = "node:" + cavern.id();
            SegmentGeometry.SegmentPlan structural =
                    SegmentGeometry.node(topology, cavern, topology.bounds(), ARRIVAL);
            // Match the generator's plan stack: motif cells count as
            // occupied so a replayed vein never expects ore where a motif
            // actually wrote.
            List<SegmentGeometry.SegmentPlan> plans = List.of(structural,
                    SegmentGeometry.dress(topology, segId, topology.bounds(), ARRIVAL),
                    MotifPlanner.planSegment(topology, segId, ARRIVAL));
            int target = jackpotIds.contains(cavern.id())
                    ? OrePlacementPlanner.jackpotVeinTarget(OrePlacementPlanner.RESOURCE_FIELD_VEINS)
                    : OrePlacementPlanner.ORDINARY_VEINS;
            SegmentGeometry.SegmentPlan ores = OrePlacementPlanner.planOreSegment(
                    "ores:" + segId, plans, OreCatalog.active(),
                    pos -> OreCatalog.Tier.parse(regionForY(pos.y())), target,
                    data.seed(), topology.bounds(),
                    pos -> SegmentGeometry.isArrivalKeepOut(pos, ARRIVAL)
                            || HubLayout.isReserved(pos)
                            || landmarks.isReserved(pos)
                            || protectedCells.contains(pos));
            int verified = 0;
            for (var op : ores.operations()) {
                BlockPos pos = new BlockPos(op.position().x(), op.position().y(), op.position().z());
                var key = BuiltInRegistries.BLOCK.getKey(mine.getBlockState(pos).getBlock());
                if (key.toString().equals(op.blockId())) {
                    verified++;
                }
            }
            verifiedCounts.put(cavern.id(), verified);
        }

        for (String region : regionsWithCaverns.keySet()) {
            int jackpotCount = caverns.stream()
                    .filter(node -> node.region().equals(region) && jackpotIds.contains(node.id()))
                    .mapToInt(node -> verifiedCounts.getOrDefault(node.id(), 0))
                    .sum();
            helper.assertTrue(jackpotCount > OrePlacementPlanner.RESOURCE_FIELD_VEINS,
                    "jackpot cavern(s) in " + region + " verified " + jackpotCount
                            + " ore blocks - below the resource-field baseline");
            for (TopoNode node : caverns) {
                if (node.region().equals(region) && !jackpotIds.contains(node.id())) {
                    int other = verifiedCounts.getOrDefault(node.id(), 0);
                    helper.assertTrue(jackpotCount > other,
                            "jackpot cavern in " + region + " verified " + jackpotCount
                                    + " ore blocks, ordinary cavern " + node.id()
                                    + " verified " + other);
                }
            }
        }
        helper.succeed();
    }

    private static Set<TopoPos> checkpointPads(TopologyPlan topology,
                                               PassageNetwork network) {
        Set<TopoPos> pads = new HashSet<>();
        for (TopoNode node : topology.nodes()) {
            if (node.has(NodeFeature.CHECKPOINT)) {
                TopoPos center = network.center(node.id());
                if (center == null) {
                    continue;
                }
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        for (int dy = -1; dy <= 2; dy++) {
                            pads.add(center.offset(dx, dy, dz));
                        }
                    }
                }
            }
        }
        return pads;
    }

    private static String regionForY(int y) {
        String found = "upper";
        String lastKnownTier = "upper";
        for (var region : TopologyProfiles.active().regions()) {
            if (OreCatalog.Tier.isKnown(region.id())) {
                lastKnownTier = region.id();
            }
            if (y <= region.topY() && y >= region.bottomY()) {
                found = OreCatalog.Tier.isKnown(region.id())
                        ? region.id() : lastKnownTier;
            }
        }
        return found;
    }
}
