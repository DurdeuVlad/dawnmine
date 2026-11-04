package com.dwurdy.dawnmine.run;

import com.dwurdy.dawnmine.generation.OreCatalog;
import com.dwurdy.dawnmine.gametest.MockServerPlayers;
import com.dwurdy.dawnmine.progression.ProgressionGraph;
import java.time.LocalDate;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * M6 progression-economy invariants: banked materials live outside
 * mine-owned reset state, combat rewards cannot duplicate through death
 * retry or run reset, and the verified upgrade step changes what a player
 * can harvest. These run in any GameTest environment; the exact-pack
 * runtime audit covers the live recipe and registry side.
 */
@GameTestHolder("dawnmine")
@PrefixGameTestTemplate(false)
public final class MineProgressionGameTests {
    private MineProgressionGameTests() { }

    private static String nextRunId(MineRunData data) {
        return data.dayId() + "-progression-" + (data.runSequence() + 1) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Simulates what the daily reset does to the run record: a brand-new
     * run id/seed/day sequence while player-owned state stays untouched. */
    private static void beginNextRun(MineRunData data) {
        data.beginNewRun(nextRunId(data), data.runSequence() + 1,
                data.seed() ^ 0x5DEECE66DL, LocalDate.now().toString());
    }

    private static ServerPlayer fakePlayer(GameTestHelper helper) {
        return MockServerPlayers.inLevel(helper);
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void bankedMaterialsSurviveNewRunReset(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MineManager manager = MineManager.get(level.getServer());
        MineRunData data = manager.data();
        CompoundTag snapshot = data.snapshot(level.registryAccess());
        try {
            // GameTestServer runs the flat preset, so the custom mine
            // dimension is absent and manager.initialize() cannot produce a
            // run here; drive the same run-lifecycle transition directly.
            beginNextRun(data);
            ServerPlayer player = fakePlayer(helper);
            player.getInventory().clearContent();
            ItemStack iron = new ItemStack(Items.RAW_IRON, 12);
            ItemStack diamond = new ItemStack(Items.DIAMOND, 2);
            helper.assertTrue(player.getInventory().add(iron.copy()), "test inventory rejected iron");
            helper.assertTrue(player.getInventory().add(diamond.copy()), "test inventory rejected diamond");

            BlockPos chestPos = helper.absolutePos(new BlockPos(2, 1, 2));
            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
            ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(chestPos);
            chest.setItem(0, new ItemStack(Items.RAW_GOLD, 7));
            chest.setItem(13, new ItemStack(Items.DIAMOND, 3));
            level.getChunkAt(chestPos).setUnsaved(true);

            String oldRunId = data.runId();
            beginNextRun(data);
            helper.assertFalse(data.runId().equals(oldRunId), "reset did not issue a new run id");
            helper.assertTrue(data.mineEntities().isEmpty(), "reset kept run entities");
            helper.assertTrue(data.checkpointStations().isEmpty(), "reset kept run checkpoints");

            helper.assertTrue(player.getInventory().countItem(Items.RAW_IRON) == 12,
                    "run reset removed banked raw iron from a player");
            helper.assertTrue(player.getInventory().countItem(Items.DIAMOND) == 2,
                    "run reset removed banked diamonds from a player");
            ChestBlockEntity after = (ChestBlockEntity) level.getBlockEntity(chestPos);
            helper.assertTrue(after.getItem(0).getCount() == 7
                            && after.getItem(0).is(Items.RAW_GOLD),
                    "run reset touched a surface bank chest");
            helper.assertTrue(after.getItem(13).getCount() == 3
                            && after.getItem(13).is(Items.DIAMOND),
                    "run reset touched stored diamonds");

            HolderLookup.Provider registries = level.registryAccess();
            CompoundTag saved = data.save(new CompoundTag(), registries);
            MineRunData restored = MineRunData.load(saved, registries);
            helper.assertValueEqual(restored.runId(), data.runId(), "new run id lost across save/load");
            helper.assertTrue(restored.mineEntities().isEmpty()
                            && restored.checkpointStations().isEmpty(),
                    "run-scoped state reappeared after save/load");
        } finally {
            data.restoreSnapshot(snapshot, level.registryAccess());
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void rewardsDoNotDuplicateAcrossRetryOrReset(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MineManager manager = MineManager.get(level.getServer());
        MineRunData data = manager.data();
        CompoundTag snapshot = data.snapshot(level.registryAccess());
        try {
            // Same GameTestServer limitation as above: begin the run and its
            // owned volume directly instead of manager.initialize().
            beginNextRun(data);
            data.addOwnedVolume(new OwnedVolume("m6_reward_audit", MineManager.MINE_DIMENSION,
                    new BlockPos(-1000, level.getMinBuildHeight(), -1000),
                    new BlockPos(1000, level.getMaxBuildHeight(), 1000)));
            data.markStatus(RunStatus.READY);
            UUID mob = UUID.randomUUID();
            ResourceLocation type = ResourceLocation.parse("minecraft:zombie");
            helper.assertTrue(data.registerMineEntity(mob, type, MineManager.MINE_DIMENSION,
                    new BlockPos(8, 70, 8), "encounter:m6_test:m6_node", "upper"),
                    "could not register the reward fixture entity");
            helper.assertTrue(data.claimMineReward(mob), "first reward claim was refused");
            helper.assertFalse(data.claimMineReward(mob),
                    "a retried death callback duplicated the reward claim");
            UUID second = UUID.randomUUID();
            helper.assertFalse(data.claimMineReward(second),
                    "an untracked entity claimed a reward");

            beginNextRun(data);
            helper.assertFalse(data.hasClaimedMineReward(mob),
                    "run reset kept the old run's reward claims");
            helper.assertTrue(data.mineEntity(mob) == null,
                    "run reset kept the old run's entity origin — a stale death could still pay out");
            helper.assertFalse(data.claimMineReward(mob),
                    "a stale pre-reset entity claimed a reward in the new run");
        } finally {
            data.restoreSnapshot(snapshot, level.registryAccess());
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void upgradeChangesWhatAPlayerCanHarvest(GameTestHelper helper) {
        // The verified depth gate: diamond-tier picks open the only nodes
        // that refuse iron. This is the mechanical meaning of the
        // steel->diamond pickaxe upgrade.
        ItemStack ironPick = new ItemStack(Items.IRON_PICKAXE);
        ItemStack diamondPick = new ItemStack(Items.DIAMOND_PICKAXE);
        ItemStack woodPick = new ItemStack(Items.WOODEN_PICKAXE);
        helper.assertFalse(ironPick.isCorrectToolForDrops(Blocks.ANCIENT_DEBRIS.defaultBlockState()),
                "an iron pickaxe harvested ancient debris");
        helper.assertTrue(diamondPick.isCorrectToolForDrops(Blocks.ANCIENT_DEBRIS.defaultBlockState()),
                "a diamond pickaxe could not harvest ancient debris");
        helper.assertFalse(woodPick.isCorrectToolForDrops(Blocks.DIAMOND_ORE.defaultBlockState()),
                "a wooden pickaxe harvested diamond ore");
        helper.assertTrue(ironPick.isCorrectToolForDrops(Blocks.DIAMOND_ORE.defaultBlockState()),
                "an iron pickaxe could not harvest diamond ore");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void progressionGraphLoadsAndDegradesGracefully(GameTestHelper helper) {
        ProgressionGraph.ProgressionSnapshot graph = ProgressionGraph.active();
        helper.assertFalse(graph.materials().isEmpty(), "progression graph loaded no materials");
        helper.assertFalse(graph.upgrades().isEmpty(), "progression graph loaded no upgrades");
        ProgressionGraph.Completeness completeness = graph.completeness();
        helper.assertTrue(completeness.unknownMiningBlocks().isEmpty(),
                "graph references ore blocks outside the catalog: "
                        + completeness.unknownMiningBlocks());
        helper.assertTrue(completeness.unknownRewardTiers().isEmpty(),
                "graph references unconfigured reward tiers: " + completeness.unknownRewardTiers());
        helper.assertTrue(completeness.miningDropMismatches().isEmpty(),
                "graph claims ore drops the catalog does not produce: "
                        + completeness.miningDropMismatches());
        // On a dev runtime without the pack, mod items are flagged
        // unavailable instead of silently trusted; the graph still reports.
        helper.assertFalse(graph.fingerprint().isBlank(), "graph has no version fingerprint");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void oreCatalogStillCoversEveryProgressionMiningSource(GameTestHelper helper) {
        // Cross-check at runtime: every progression mining source is an
        // ore-catalog entry whose drops include the material. Dev runtimes
        // report mod blocks as unavailable rather than inventing them.
        OreCatalog.OreCatalogSnapshot ores = OreCatalog.active();
        ProgressionGraph.ProgressionSnapshot graph = ProgressionGraph.active();
        for (ProgressionGraph.Material material : graph.materials().values()) {
            for (ProgressionGraph.Source source : material.sources()) {
                if (source.kind() != ProgressionGraph.SourceKind.MINING) continue;
                net.minecraft.resources.ResourceLocation block =
                        ResourceLocation.parse(source.ref().substring(6));
                helper.assertTrue(ores.entries().containsKey(block),
                        "progression mines " + block + " which is not catalogued");
            }
        }
        helper.succeed();
    }
}
