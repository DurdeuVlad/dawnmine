package com.dwurdy.dawnmine.generation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** M12a: rare ores stay small, common ores stay larger - never the old 6-17 block floor. */
class OreRarityTest {
    @Test
    void rarestOresAreTiny() {
        for (String id : new String[] {"minecraft:ancient_debris", "minecraft:emerald_ore",
                "minecraft:deepslate_emerald_ore", "jewelry:deepslate_gem_vein",
                "born_in_chaos_v1:dark_metal_deposit"}) {
            OreRarity.Range r = OreRarity.sizeFor(id);
            assertTrue(r.max() <= 2, id + " should cap at 2 blocks, got " + r);
        }
    }

    @Test
    void diamondIsSmallButLargerThanTheRarestTier() {
        OreRarity.Range diamond = OreRarity.sizeFor("minecraft:diamond_ore");
        OreRarity.Range emerald = OreRarity.sizeFor("minecraft:emerald_ore");
        assertTrue(diamond.max() <= 3, "diamond should still read as a small find, got " + diamond);
        assertTrue(diamond.max() >= emerald.max(), "diamond should not be smaller than emerald");
    }

    @Test
    void commodityOresAreTheLargestVeins() {
        OreRarity.Range coal = OreRarity.sizeFor("minecraft:coal_ore");
        OreRarity.Range iron = OreRarity.sizeFor("minecraft:iron_ore");
        OreRarity.Range diamond = OreRarity.sizeFor("minecraft:diamond_ore");
        assertTrue(coal.min() > diamond.max(), "coal's smallest vein should exceed diamond's largest");
        assertTrue(iron.min() > diamond.max(), "iron's smallest vein should exceed diamond's largest");
    }

    @Test
    void unknownEntryGetsAConservativeDefaultNotTheOldFloor() {
        OreRarity.Range unknown = OreRarity.sizeFor("somemod:unlisted_ore");
        assertTrue(unknown.max() <= 5, "unranked ore should default to a modest size, got " + unknown);
    }
}
