package com.dwurdy.dawnmine.generation;

import java.util.Map;

/**
 * M12a: per-ore vein size, researched from rarity rather than reusing {@code max_vein_size}
 * (that field is a native-break cluster cap from the old M3 ore ecology, not a generation-vein
 * size, and treating it as one made every ore's vein far too large regardless of rarity).
 *
 * <p>Bands below combine the catalog's own tier/weight signal with ordinary ore-rarity knowledge:
 * commodity ores that should feel abundant (coal/copper/iron, and nether quartz as the chaos-tier
 * commodity) get the largest veins; ordinary valuable ores (gold/lapis/redstone/silver and modded
 * equivalents) get medium veins; the handful of genuinely rare finds (diamond) get small veins; and
 * the rarest possible finds (emerald, ancient debris, gem veins, dark metal) get tiny 1-2 block
 * veins so finding one reads as a special moment. Values are a documented starting point for
 * playtest, not a measured economy rate - see {@code docs/reports/M12a-ore-vein-rarity.md}.
 */
public final class OreRarity {
    public record Range(int min, int max) {
        public Range {
            if (min < 1 || max < min) {
                throw new IllegalArgumentException("invalid vein size range " + min + ".." + max);
            }
        }
    }

    /** Conservative mid-size default for any catalog entry not explicitly ranked below. */
    private static final Range DEFAULT = new Range(3, 5);

    private static final Map<String, Range> SIZES = Map.ofEntries(
            // Commodity: abundant, low-value staples. Upper tier's coal/copper/iron and chaos's
            // own commodity (nether quartz is common within its tier, unlike the other chaos ores).
            Map.entry("minecraft:coal_ore", new Range(6, 10)),
            Map.entry("minecraft:deepslate_coal_ore", new Range(6, 10)),
            Map.entry("minecraft:copper_ore", new Range(6, 10)),
            Map.entry("minecraft:deepslate_copper_ore", new Range(6, 10)),
            Map.entry("minecraft:iron_ore", new Range(5, 9)),
            Map.entry("minecraft:deepslate_iron_ore", new Range(5, 9)),
            Map.entry("minecraft:nether_quartz_ore", new Range(6, 10)),

            // Uncommon: still ordinary mining finds, a notch below commodity.
            Map.entry("create:zinc_ore", new Range(4, 7)),
            Map.entry("create:deepslate_zinc_ore", new Range(4, 7)),
            Map.entry("butchery:sulfur_ore", new Range(3, 6)),
            Map.entry("butchery:deepslate_sulfur_ore", new Range(3, 6)),
            Map.entry("fungidelight:truffle_dirt", new Range(3, 6)),
            Map.entry("minecraft:redstone_ore", new Range(4, 7)),
            Map.entry("minecraft:deepslate_redstone_ore", new Range(4, 7)),

            // Rare: real mining goals, worth a detour but not a jackpot.
            Map.entry("minecraft:gold_ore", new Range(3, 5)),
            Map.entry("minecraft:deepslate_gold_ore", new Range(3, 5)),
            Map.entry("minecraft:lapis_ore", new Range(3, 5)),
            Map.entry("minecraft:deepslate_lapis_ore", new Range(3, 5)),
            Map.entry("minecraft:nether_gold_ore", new Range(2, 4)),
            Map.entry("werewolves:silver_ore", new Range(3, 5)),
            Map.entry("werewolves:deepslate_silver_ore", new Range(3, 5)),
            Map.entry("abyssal_decor:seabrass_ore", new Range(2, 4)),

            // Very rare: diamond-tier. Small and deliberate.
            Map.entry("minecraft:diamond_ore", new Range(1, 3)),
            Map.entry("minecraft:deepslate_diamond_ore", new Range(1, 3)),
            Map.entry("jewelry:gem_vein", new Range(1, 2)),

            // Extremely rare: the rarest possible find. Always tiny.
            Map.entry("minecraft:emerald_ore", new Range(1, 2)),
            Map.entry("minecraft:deepslate_emerald_ore", new Range(1, 2)),
            Map.entry("minecraft:ancient_debris", new Range(1, 2)),
            Map.entry("jewelry:deepslate_gem_vein", new Range(1, 2)),
            Map.entry("born_in_chaos_v1:dark_metal_deposit", new Range(1, 2)));

    private OreRarity() { }

    public static Range sizeFor(String blockId) {
        return SIZES.getOrDefault(blockId, DEFAULT);
    }
}
