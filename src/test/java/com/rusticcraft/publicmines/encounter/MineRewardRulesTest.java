package com.rusticcraft.publicmines.encounter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rusticcraft.publicmines.run.MineManager;
import com.rusticcraft.publicmines.run.MineRunData.MineEntityOrigin;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class MineRewardRulesTest {
    private static final BlockPos POS = new BlockPos(10, 20, 30);
    private static final MineRewardCatalog.Reward REWARD = new MineRewardCatalog.Reward(
            "upper", ResourceLocation.parse("minecraft:raw_iron"), 2_000, 1, 1, "purpose", "source and sink");

    private static MineEntityOrigin origin(String runId, ResourceKey<Level> dimension) {
        return new MineEntityOrigin(UUID.randomUUID(), runId,
                ResourceLocation.parse("born_in_chaos_v1:decrepit_skeleton"), dimension,
                POS, "encounter:upper_lone_pursuer:node-1", "upper");
    }

    @Test
    void onlyCurrentRunOwnedConfiguredDirectPlayerDeathsWithTierRewardAreEligible() {
        MineEntityOrigin origin = origin("run-1", MineManager.MINE_DIMENSION);
        assertTrue(MineRewardRules.eligible(origin, "run-1", MineManager.MINE_DIMENSION,
                POS, true, true, true, REWARD));
        assertFalse(MineRewardRules.eligible(origin, "old-run", MineManager.MINE_DIMENSION,
                POS, true, true, true, REWARD), "stale run provenance must not reward");
        assertFalse(MineRewardRules.eligible(origin("run-1", Level.OVERWORLD), "run-1",
                MineManager.MINE_DIMENSION, POS, true, true, true, REWARD),
                "entity from another dimension must not reward");
        assertFalse(MineRewardRules.eligible(origin, "run-1", MineManager.MINE_DIMENSION,
                POS, false, true, true, REWARD), "outside-owned-bound death must not reward");
        assertFalse(MineRewardRules.eligible(origin, "run-1", MineManager.MINE_DIMENSION,
                POS, true, false, true, REWARD), "environmental or non-player death must not reward");
        assertFalse(MineRewardRules.eligible(origin, "run-1", MineManager.MINE_DIMENSION,
                POS, true, true, false, REWARD), "unconfigured mob/source must not reward");
        assertFalse(MineRewardRules.eligible(origin, "run-1", MineManager.MINE_DIMENSION,
                POS, true, true, true, null), "missing tier reward must fail closed");
    }

    @Test
    void chanceRollBoundariesAreExactAndInvalidValuesFailClosed() {
        assertTrue(MineRewardRules.rollSucceeded(2_000, 0));
        assertTrue(MineRewardRules.rollSucceeded(2_000, 1_999));
        assertFalse(MineRewardRules.rollSucceeded(2_000, 2_000));
        assertFalse(MineRewardRules.rollSucceeded(0, 0));
        assertFalse(MineRewardRules.rollSucceeded(10_001, 0));
        assertFalse(MineRewardRules.rollSucceeded(2_000, -1));
        assertFalse(MineRewardRules.rollSucceeded(2_000, 10_000));
    }
}
