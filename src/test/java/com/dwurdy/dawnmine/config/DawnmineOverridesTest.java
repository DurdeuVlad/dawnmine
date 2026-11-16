package com.dwurdy.dawnmine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The in-memory override store's set/get/reset round-trip, one per category - the base+override
 * merge itself happens in each consuming system (CaveTerrain, OreField, MineEncounterManager,
 * MobLevelScaling, MineLootManager); this only verifies the store they all read from behaves.
 */
class DawnmineOverridesTest {
    @AfterEach
    void clearEverything() {
        DawnmineOverrides.resetOre("upper", "minecraft:coal_ore");
        DawnmineOverrides.resetDensity("upper");
        DawnmineOverrides.resetMobs("nether");
        DawnmineOverrides.resetDifficulty("3-5");
        DawnmineOverrides.resetLoot("chaos");
    }

    @Test
    void oreOverrideSetGetReset() {
        assertTrue(DawnmineOverrides.ore("upper", "minecraft:coal_ore").isEmpty());
        DawnmineOverrides.setOreWeight("upper", "minecraft:coal_ore", 42);
        assertEquals(42, DawnmineOverrides.ore("upper", "minecraft:coal_ore").orElseThrow().weight());
        // Setting vein size afterward keeps the weight already set (fields are independent).
        DawnmineOverrides.setOreVeinSize("upper", "minecraft:coal_ore", 2, 9);
        var o = DawnmineOverrides.ore("upper", "minecraft:coal_ore").orElseThrow();
        assertEquals(42, o.weight());
        assertEquals(2, o.veinMin());
        assertEquals(9, o.veinMax());
        DawnmineOverrides.resetOre("upper", "minecraft:coal_ore");
        assertTrue(DawnmineOverrides.ore("upper", "minecraft:coal_ore").isEmpty());
    }

    @Test
    void densityOverrideSetGetReset() {
        assertTrue(DawnmineOverrides.density("upper").isEmpty());
        DawnmineOverrides.setDensity("upper", 2.5);
        assertEquals(2.5, DawnmineOverrides.density("upper").orElseThrow());
        DawnmineOverrides.resetDensity("upper");
        assertTrue(DawnmineOverrides.density("upper").isEmpty());
    }

    @Test
    void mobRosterAddRemoveAreMutuallyExclusive() {
        DawnmineOverrides.addMob("nether", "minecraft:blaze");
        assertTrue(DawnmineOverrides.mobAdds("nether").contains("minecraft:blaze"));
        assertFalse(DawnmineOverrides.mobRemovals("nether").contains("minecraft:blaze"));
        // Removing an entity that was added un-adds it rather than tracking it as both.
        DawnmineOverrides.removeMob("nether", "minecraft:blaze");
        assertFalse(DawnmineOverrides.mobAdds("nether").contains("minecraft:blaze"));
        assertTrue(DawnmineOverrides.mobRemovals("nether").contains("minecraft:blaze"));
        DawnmineOverrides.resetMobs("nether");
        assertTrue(DawnmineOverrides.mobAdds("nether").isEmpty());
        assertTrue(DawnmineOverrides.mobRemovals("nether").isEmpty());
    }

    /** The symmetric case to the bug this store already had: removing a mob/loot entry as the
     * very first operation on a layer (no prior add) must not touch an immutable empty-set
     * fallback either, on the OTHER side of the add/remove pair than the one first caught. */
    @Test
    void removeFirstNeverTouchesAnImmutableDefault() {
        DawnmineOverrides.removeMob("nether", "minecraft:ghast");
        assertTrue(DawnmineOverrides.mobRemovals("nether").contains("minecraft:ghast"));
        DawnmineOverrides.resetMobs("nether");
        DawnmineOverrides.removeLoot("chaos", "minecraft:gold_ingot");
        assertTrue(DawnmineOverrides.lootRemovals("chaos").contains("minecraft:gold_ingot"));
        DawnmineOverrides.resetLoot("chaos");
    }

    @Test
    void difficultyOverrideSetGetResetKeepsUnsetFieldsNull() {
        assertTrue(DawnmineOverrides.difficulty("3-5").isEmpty());
        DawnmineOverrides.setDifficulty("3-5", 0.5, null, null);
        var o = DawnmineOverrides.difficulty("3-5").orElseThrow();
        assertEquals(0.5, o.health());
        assertEquals(null, o.damage());
        // Setting damage afterward must not clobber the health already set.
        DawnmineOverrides.setDifficulty("3-5", null, 0.3, null);
        o = DawnmineOverrides.difficulty("3-5").orElseThrow();
        assertEquals(0.5, o.health());
        assertEquals(0.3, o.damage());
        DawnmineOverrides.resetDifficulty("3-5");
        assertTrue(DawnmineOverrides.difficulty("3-5").isEmpty());
    }

    @Test
    void lootAddRemoveAreMutuallyExclusive() {
        DawnmineOverrides.addLoot("chaos", "minecraft:netherite_scrap", 1, 1, 1);
        assertTrue(DawnmineOverrides.lootAdds("chaos").stream()
                .anyMatch(e -> e.item().equals("minecraft:netherite_scrap")));
        DawnmineOverrides.removeLoot("chaos", "minecraft:netherite_scrap");
        assertTrue(DawnmineOverrides.lootAdds("chaos").isEmpty());
        assertTrue(DawnmineOverrides.lootRemovals("chaos").contains("minecraft:netherite_scrap"));
        DawnmineOverrides.resetLoot("chaos");
        assertTrue(DawnmineOverrides.lootAdds("chaos").isEmpty());
        assertTrue(DawnmineOverrides.lootRemovals("chaos").isEmpty());
    }
}
