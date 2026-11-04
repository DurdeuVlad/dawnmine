package com.dwurdy.dawnmine.encounter;

import com.dwurdy.dawnmine.run.MineRunData.MineEntityOrigin;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Small deterministic policy boundary for mine reward eligibility and chance. */
final class MineRewardRules {
    private MineRewardRules() { }

    static boolean eligible(MineEntityOrigin origin, String activeRunId,
                            ResourceKey<Level> activeDimension, BlockPos deathPosition,
                            boolean ownedByMine, boolean directServerPlayerAttacker,
                            boolean configuredEncounter, MineRewardCatalog.Reward reward) {
        return origin != null && activeRunId != null && origin.runId().equals(activeRunId)
                && activeDimension != null && origin.dimension().equals(activeDimension)
                && deathPosition != null
                && ownedByMine && directServerPlayerAttacker && configuredEncounter && reward != null;
    }

    static boolean rollSucceeded(int chanceBasisPoints, int roll) {
        return chanceBasisPoints > 0 && chanceBasisPoints <= 10_000
                && roll >= 0 && roll < 10_000 && roll < chanceBasisPoints;
    }
}
