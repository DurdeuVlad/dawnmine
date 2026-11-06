package com.dwurdy.dawnmine.run;

import com.dwurdy.dawnmine.progression.ProgressionGraph;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Opt-in exact-pack audit of the M6 progression economy. Restricted to a
 * disposable world; it verifies that every graph claim resolves against the
 * live registries and recipe manager, that the verified tool gate changes
 * with the upgrade, that banked materials survive a simulated daily reset
 * plus a real server restart, and that combat rewards cannot duplicate
 * through death retry or reset.
 *
 * <p>Two console-driven phases cover the repeat-run playtest:
 * {@code prepare} creates/validates run A and banks materials in an
 * overworld chest (the same player-owned, outside-run-state guarantee
 * the Dispatch locker now provides), then the
 * operator restarts the server; {@code verify} reloads, confirms the chest
 * survived the restart, simulates the next daily run (a fresh run id —
 * the reset behavior M7/#10 will schedule), and re-checks the bank and the
 * run-scoped cleanup. {@code all} runs the same checks in a single session
 * for development.
 */
public final class MineProgressionRuntimeAudit {
    private static final String TEMPORARY_WORLD_NAME = "dawnmine-m6-progression-audit";
    private static final ResourceLocation STEEL_PICKAXE =
            ResourceLocation.parse("overgeared:steel_pickaxe");
    private static final ResourceLocation INFECTED_DIAMOND_ORE =
            ResourceLocation.parse("born_in_chaos_v1:infected_diamond_ore");
    private static final ResourceLocation INFECTED_DEEPSLATE_DIAMOND_ORE =
            ResourceLocation.parse("born_in_chaos_v1:infected_deepslate_diamond_ore");
    private static final ResourceLocation DIAMOND_TERMITE =
            ResourceLocation.parse("born_in_chaos_v1:diamond_termite");
    private static final BlockPos BANK_POS = new BlockPos(24, 80, 24);

    private MineProgressionRuntimeAudit() { }

    public record Result(int cases, List<String> evidence, List<String> failures) {
        public Result {
            evidence = List.copyOf(evidence);
            failures = List.copyOf(failures);
        }

        public boolean passed() { return failures.isEmpty() && cases > 0; }
    }

    public static Result run(ServerLevel overworld, String phase) {
        List<String> evidence = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        if (overworld == null || overworld.getServer() == null) {
            return new Result(0, evidence, List.of("no overworld is available"));
        }
        if (!TEMPORARY_WORLD_NAME.equals(overworld.getServer().getWorldData().getLevelName())) {
            return new Result(0, evidence, List.of("audit is restricted to disposable world '"
                    + TEMPORARY_WORLD_NAME + "'; current world is '"
                    + overworld.getServer().getWorldData().getLevelName() + "'"));
        }
        return switch (phase == null ? "all" : phase.toLowerCase(java.util.Locale.ROOT)) {
            case "prepare" -> runPrepare(overworld, evidence, failures);
            case "verify" -> runVerify(overworld, evidence, failures);
            case "all" -> runAll(overworld, evidence, failures);
            default -> new Result(0, evidence, List.of("unknown phase '" + phase
                    + "' (expected prepare, verify, or all)"));
        };
    }

    // ------------------------------------------------------------------
    // Shared checks
    // ------------------------------------------------------------------

    private static int graphResolution(ServerLevel overworld, List<String> evidence,
                                       List<String> failures) {
        ProgressionGraph.ProgressionSnapshot graph = ProgressionGraph.active();
        if (graph.materials().isEmpty()) {
            failures.add("progression graph is empty or failed to load");
            return 0;
        }
        int cases = 1;
        for (ProgressionGraph.Material material : graph.materials().values()) {
            for (ResourceLocation item : material.allItems()) {
                if (BuiltInRegistries.ITEM.getOptional(item).isEmpty()) {
                    failures.add("material drop item is not registered: " + item);
                }
            }
        }
        for (ProgressionGraph.Upgrade upgrade : graph.upgrades()) {
            if (BuiltInRegistries.ITEM.getOptional(upgrade.output()).isEmpty()) {
                failures.add("upgrade output is not registered: " + upgrade.output()
                        + " (upgrade " + upgrade.id() + ")");
            }
        }
        for (ProgressionGraph.BlockedNode node : graph.blocked()) {
            if (BuiltInRegistries.ITEM.getOptional(node.item()).isEmpty()) {
                failures.add("blocked item is not even registered in this pack: " + node.item()
                        + " — review the gap claim");
            }
        }
        ProgressionGraph.Completeness completeness = graph.completeness();
        if (!completeness.complete()) {
            failures.add("progression graph completeness failed: " + completeness);
        }
        List<String> recipeFailures = ProgressionGraph.verifyRecipes(
                overworld.getServer().getRecipeManager(), overworld.registryAccess());
        failures.addAll(recipeFailures);
        evidence.add("graph resolution: " + graph.adminSummary()
                + ", integrity=" + completeness.complete()
                + ", recipeFailures=" + recipeFailures.size());
        return cases;
    }

    private static int toolGates(List<String> evidence, List<String> failures) {
        Item steelPick = BuiltInRegistries.ITEM.getOptional(STEEL_PICKAXE).orElse(null);
        ItemStack steel = steelPick == null ? ItemStack.EMPTY : new ItemStack(steelPick);
        ItemStack diamondPick = new ItemStack(Items.DIAMOND_PICKAXE);
        ItemStack ironPick = new ItemStack(Items.IRON_PICKAXE);
        BlockState debris = Blocks.ANCIENT_DEBRIS.defaultBlockState();
        if (steelPick == null) {
            failures.add("overgeared:steel_pickaxe is not registered in this pack");
        } else if (steel.isCorrectToolForDrops(debris)) {
            failures.add("a steel pickaxe harvested ancient_debris — the verified depth gate is gone");
        }
        if (!diamondPick.isCorrectToolForDrops(debris)) {
            failures.add("a diamond pickaxe cannot harvest ancient_debris");
        }
        if (!ironPick.isCorrectToolForDrops(Blocks.DIAMOND_ORE.defaultBlockState())) {
            failures.add("an iron pickaxe cannot harvest diamond_ore, contrary to the catalog minimum");
        }
        // Infected ores are not harvest-gated: any break (any tool, even
        // wrong ones) fires the summon procedure; the depth gate claim lives
        // only on ancient_debris. Report the observed breakability as
        // evidence, and only fail if the blocks or the termite are missing.
        for (ResourceLocation infected : List.of(INFECTED_DIAMOND_ORE, INFECTED_DEEPSLATE_DIAMOND_ORE)) {
            var block = BuiltInRegistries.BLOCK.getOptional(infected).orElse(null);
            if (block == null) {
                failures.add("infected ore is not registered: " + infected);
                continue;
            }
            BlockState state = block.defaultBlockState();
            evidence.add("infected ore " + infected + ": steel-correct="
                    + (steelPick != null && steel.isCorrectToolForDrops(state))
                    + ", diamond-correct=" + diamondPick.isCorrectToolForDrops(state)
                    + " (summon fires on any player break; not a harvest gate)");
        }
        if (BuiltInRegistries.ENTITY_TYPE.getOptional(DIAMOND_TERMITE).isEmpty()) {
            failures.add("diamond termite is not registered; the infected-ore counter cannot spawn");
        }
        evidence.add("tool gates: steelPick=" + (steelPick != null)
                + ", steel->debris=" + (steelPick != null && steel.isCorrectToolForDrops(debris))
                + ", diamond->debris=" + diamondPick.isCorrectToolForDrops(debris)
                + ", iron->diamondOre=" + ironPick.isCorrectToolForDrops(Blocks.DIAMOND_ORE.defaultBlockState()));
        return 1;
    }

    private static int bankedMaterialsPersistAcrossReset(ServerLevel overworld,
            List<String> evidence, List<String> failures, boolean simulateNewRun) {
        MineManager manager = MineManager.get(overworld.getServer());
        MineRunData data = manager.data();
        overworld.getChunkAt(BANK_POS);
        var blockEntity = overworld.getBlockEntity(BANK_POS);
        if (!(blockEntity instanceof ChestBlockEntity chest)) {
            failures.add("bank chest is missing at " + BANK_POS + " (was the prepare phase run?)");
            return 0;
        }
        ItemStack slot0 = chest.getItem(0);
        ItemStack slot13 = chest.getItem(13);
        if (!(slot0.is(Items.RAW_IRON) && slot0.getCount() == 12
                && slot13.is(Items.DIAMOND) && slot13.getCount() == 3)) {
            failures.add("bank chest contents changed: " + slot0 + " / " + slot13);
        } else {
            evidence.add("bank chest intact: " + slot0 + " and " + slot13);
        }
        if (simulateNewRun) {
            String oldRun = data.runId();
            data.beginNewRun(LocalDate.now() + "-m6-audit-" + UUID.randomUUID().toString().substring(0, 8),
                    data.runSequence() + 1, data.seed() ^ 0x364D4C36L, LocalDate.now().toString());
            if (data.runId().equals(oldRun)) {
                failures.add("simulated reset did not issue a new run id");
            }
            if (!data.mineEntities().isEmpty() || !data.checkpointStations().isEmpty()) {
                failures.add("simulated reset kept run-scoped entities/checkpoints");
            }
            var after = overworld.getBlockEntity(BANK_POS);
            if (after instanceof ChestBlockEntity afterChest
                    && afterChest.getItem(0).is(Items.RAW_IRON)
                    && afterChest.getItem(0).getCount() == 12
                    && afterChest.getItem(13).is(Items.DIAMOND)
                    && afterChest.getItem(13).getCount() == 3) {
                evidence.add("bank chest survived a simulated daily reset into run " + data.runId());
            } else {
                failures.add("simulated reset disturbed the bank chest");
            }
            data.markStatus(RunStatus.READY);
        }
        return 1;
    }

    private static int rewardsCannotDuplicate(ServerLevel overworld, MineRunData data,
            List<String> evidence, List<String> failures) {
        int radius = 32;
        data.addOwnedVolume(new OwnedVolume("m6_audit_owned", MineManager.MINE_DIMENSION,
                new BlockPos(-radius * 16, overworld.getMinBuildHeight(), -radius * 16),
                new BlockPos(radius * 16 - 1, overworld.getMaxBuildHeight(), radius * 16 - 1)));
        UUID mob = UUID.randomUUID();
        if (!data.registerMineEntity(mob, ResourceLocation.parse("born_in_chaos_v1:decrepit_skeleton"),
                MineManager.MINE_DIMENSION, new BlockPos(8, 64, 8), "encounter:m6_audit:m6", "upper")) {
            failures.add("could not register the reward fixture entity in the audit run");
            return 0;
        }
        if (!data.claimMineReward(mob)) {
            failures.add("first reward claim was refused");
        }
        if (data.claimMineReward(mob)) {
            failures.add("a retried death callback duplicated the reward");
        }
        String oldRun = data.runId();
        data.beginNewRun(oldRun + "-next", data.runSequence() + 1, data.seed() ^ 0x364D4C36L,
                LocalDate.now().toString());
        if (data.hasClaimedMineReward(mob) || data.mineEntity(mob) != null) {
            failures.add("reset kept the old run's reward claim/entity — stale kills could pay out");
        }
        if (data.claimMineReward(mob)) {
            failures.add("a stale pre-reset entity claimed a reward in the new run");
        }
        data.markStatus(RunStatus.READY);
        evidence.add("reward claims: duplicate refused, stale-run entity refused after reset");
        return 1;
    }

    private static int expectedYieldsBounded(List<String> evidence, List<String> failures) {
        int cases = 0;
        var rewards = com.dwurdy.dawnmine.encounter.MineRewardCatalog.all();
        int[] caps = {8, 12, 16};
        String[] tiers = {"upper", "middle", "chaos"};
        for (int i = 0; i < tiers.length; i++) {
            var reward = rewards.get(tiers[i]);
            if (reward == null) {
                failures.add("reward tier " + tiers[i] + " is not configured");
                continue;
            }
            double expected = caps[i] * reward.chanceBasisPoints() / 10_000.0;
            if (expected > caps[i] * reward.maxCount()) {
                failures.add("reward tier " + tiers[i] + " expected yield exceeds its own bound");
            }
            evidence.add("yield " + tiers[i] + ": <= " + caps[i] + " tracked kills x "
                    + reward.chancePercent() + "% x " + reward.minCount() + "-" + reward.maxCount()
                    + " = ~" + String.format(java.util.Locale.ROOT, "%.2f", expected)
                    + " " + reward.itemId() + " expected per run");
            cases++;
        }
        return cases;
    }

    // ------------------------------------------------------------------
    // Phases
    // ------------------------------------------------------------------

    private static Result runPrepare(ServerLevel overworld, List<String> evidence,
                                     List<String> failures) {
        int cases = 0;
        MineManager manager = MineManager.get(overworld.getServer());
        List<MineManager.Step> steps = manager.initialize();
        for (MineManager.Step step : steps) {
            evidence.add("init " + step.name() + ": " + (step.ok() ? "ok" : "FAIL") + " " + step.detail());
            if (!step.ok()) failures.add("initialization failed at " + step.name() + ": " + step.detail());
        }
        if (!failures.isEmpty()) return new Result(cases, evidence, failures);
        cases++;

        overworld.getChunkAt(BANK_POS);
        overworld.setBlock(BANK_POS, Blocks.CHEST.defaultBlockState(), 3);
        var blockEntity = overworld.getBlockEntity(BANK_POS);
        if (blockEntity instanceof ChestBlockEntity chest) {
            chest.setItem(0, new ItemStack(Items.RAW_IRON, 12));
            chest.setItem(13, new ItemStack(Items.DIAMOND, 3));
            overworld.getChunkAt(BANK_POS).setUnsaved(true);
            cases++;
            evidence.add("banked 12 raw_iron + 3 diamond in overworld chest at " + BANK_POS
                    + " for run " + manager.data().runId() + " — restart, then run phase 'verify'");
        } else {
            failures.add("could not place the bank chest at " + BANK_POS);
        }
        cases += graphResolution(overworld, evidence, failures);
        cases += toolGates(evidence, failures);
        cases += expectedYieldsBounded(evidence, failures);
        return new Result(cases, evidence, failures);
    }

    private static Result runVerify(ServerLevel overworld, List<String> evidence,
                                    List<String> failures) {
        int cases = 0;
        cases += bankedMaterialsPersistAcrossReset(overworld, evidence, failures, true);
        MineRunData data = MineManager.get(overworld.getServer()).data();
        cases += rewardsCannotDuplicate(overworld, data, evidence, failures);
        cases += graphResolution(overworld, evidence, failures);
        cases += toolGates(evidence, failures);
        return new Result(cases, evidence, failures);
    }

    private static Result runAll(ServerLevel overworld, List<String> evidence, List<String> failures) {
        Result prepare = runPrepare(overworld, evidence, failures);
        if (!prepare.failures().isEmpty()) return new Result(prepare.cases(), evidence, failures);
        Result verify = runVerify(overworld, evidence, failures);
        return new Result(prepare.cases() + verify.cases(), evidence, failures);
    }
}
