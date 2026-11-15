package com.dwurdy.dawnmine.encounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MobLevelScalingTest {
    @Test
    void easiestLevelsKeepNativeStats() {
        assertEquals(0.0, MobLevelScaling.healthBonusMultiplier(1));
        assertEquals(0.0, MobLevelScaling.healthBonusMultiplier(2));
        assertEquals(0.0, MobLevelScaling.damageBonusMultiplier(1));
        assertEquals(0.0, MobLevelScaling.speedBonusMultiplier(1));
    }

    @Test
    void bandBoundariesAreExact() {
        assertEquals(MobLevelScaling.HEALTH_BAND_3_TO_5, MobLevelScaling.healthBonusMultiplier(3));
        assertEquals(MobLevelScaling.HEALTH_BAND_3_TO_5, MobLevelScaling.healthBonusMultiplier(5));
        assertEquals(MobLevelScaling.HEALTH_BAND_6_TO_8, MobLevelScaling.healthBonusMultiplier(6));
        assertEquals(MobLevelScaling.HEALTH_BAND_6_TO_8, MobLevelScaling.healthBonusMultiplier(8));
        assertEquals(MobLevelScaling.HEALTH_BAND_9_PLUS, MobLevelScaling.healthBonusMultiplier(9));
        assertEquals(MobLevelScaling.HEALTH_BAND_9_PLUS, MobLevelScaling.healthBonusMultiplier(16));
    }

    @Test
    void damageAndSpeedScaleTogetherWithHealth() {
        for (int level : new int[] {3, 5, 6, 8, 9, 20}) {
            double health = MobLevelScaling.healthBonusMultiplier(level);
            double damage = MobLevelScaling.damageBonusMultiplier(level);
            double speed = MobLevelScaling.speedBonusMultiplier(level);
            assertTrue(health > 0 && damage > 0 && speed > 0,
                    "level " + level + " must scale all three stats together, got health="
                            + health + " damage=" + damage + " speed=" + speed);
        }
    }

    @Test
    void bonusIsBoundedAndMonotonic() {
        double previousHealth = -1.0;
        double previousDamage = -1.0;
        double previousSpeed = -1.0;
        for (int level = 1; level <= 16; level++) {
            double health = MobLevelScaling.healthBonusMultiplier(level);
            double damage = MobLevelScaling.damageBonusMultiplier(level);
            double speed = MobLevelScaling.speedBonusMultiplier(level);
            assertTrue(health >= previousHealth, "health bonus decreased at level " + level);
            assertTrue(damage >= previousDamage, "damage bonus decreased at level " + level);
            assertTrue(speed >= previousSpeed, "speed bonus decreased at level " + level);
            assertTrue(health >= 0.0 && health <= MobLevelScaling.HEALTH_BAND_9_PLUS,
                    "health bonus out of bounds at level " + level);
            assertTrue(damage >= 0.0 && damage <= MobLevelScaling.DAMAGE_BAND_9_PLUS,
                    "damage bonus out of bounds at level " + level);
            assertTrue(speed >= 0.0 && speed <= MobLevelScaling.SPEED_BAND_9_PLUS,
                    "speed bonus out of bounds at level " + level);
            previousHealth = health;
            previousDamage = damage;
            previousSpeed = speed;
        }
    }

    @Test
    void outOfTableLevelsClampIntoExtremeBands() {
        assertEquals(0.0, MobLevelScaling.healthBonusMultiplier(0));
        assertEquals(0.0, MobLevelScaling.healthBonusMultiplier(-5));
        assertEquals(MobLevelScaling.HEALTH_BAND_9_PLUS, MobLevelScaling.healthBonusMultiplier(1000));
        assertEquals(MobLevelScaling.DAMAGE_BAND_9_PLUS, MobLevelScaling.damageBonusMultiplier(1000));
        assertEquals(MobLevelScaling.SPEED_BAND_9_PLUS, MobLevelScaling.speedBonusMultiplier(1000));
    }

    @Test
    void colorEscalatesWithLevel() {
        assertEquals(net.minecraft.ChatFormatting.GRAY, MobLevelScaling.colorFor(1));
        assertEquals(net.minecraft.ChatFormatting.YELLOW, MobLevelScaling.colorFor(4));
        assertEquals(net.minecraft.ChatFormatting.GOLD, MobLevelScaling.colorFor(7));
        assertEquals(net.minecraft.ChatFormatting.RED, MobLevelScaling.colorFor(9));
    }
}
