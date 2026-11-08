package com.dwurdy.dawnmine.encounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MobLevelScalingTest {
    @Test
    void easiestLevelsKeepNativeStats() {
        assertEquals(0.0, MobLevelScaling.healthBonusMultiplier(1));
        assertEquals(0.0, MobLevelScaling.healthBonusMultiplier(2));
    }

    @Test
    void bandBoundariesAreExact() {
        assertEquals(MobLevelScaling.BONUS_BAND_3_TO_5, MobLevelScaling.healthBonusMultiplier(3));
        assertEquals(MobLevelScaling.BONUS_BAND_3_TO_5, MobLevelScaling.healthBonusMultiplier(5));
        assertEquals(MobLevelScaling.BONUS_BAND_6_TO_8, MobLevelScaling.healthBonusMultiplier(6));
        assertEquals(MobLevelScaling.BONUS_BAND_6_TO_8, MobLevelScaling.healthBonusMultiplier(8));
        assertEquals(MobLevelScaling.BONUS_BAND_9_PLUS, MobLevelScaling.healthBonusMultiplier(9));
        assertEquals(MobLevelScaling.BONUS_BAND_9_PLUS, MobLevelScaling.healthBonusMultiplier(16));
    }

    @Test
    void bonusIsBoundedAndMonotonic() {
        double previous = -1.0;
        for (int level = 1; level <= 16; level++) {
            double bonus = MobLevelScaling.healthBonusMultiplier(level);
            assertTrue(bonus >= previous, "bonus decreased at level " + level);
            assertTrue(bonus >= 0.0 && bonus <= MobLevelScaling.BONUS_BAND_9_PLUS,
                    "bonus out of bounds at level " + level);
            previous = bonus;
        }
        assertTrue(MobLevelScaling.BONUS_BAND_9_PLUS <= 1.0,
                "the deepest band must remain a sub-100% bonus");
    }

    @Test
    void outOfTableLevelsClampIntoExtremeBands() {
        assertEquals(0.0, MobLevelScaling.healthBonusMultiplier(0));
        assertEquals(0.0, MobLevelScaling.healthBonusMultiplier(-5));
        assertEquals(MobLevelScaling.BONUS_BAND_9_PLUS, MobLevelScaling.healthBonusMultiplier(1000));
    }
}
