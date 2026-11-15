# M12a: ore vein sizing, researched from rarity

## Root cause

`OreField.grow` sized every vein as `5 + grade*(1+rand(4))`, where `grade` was
`OreCatalog.Entry.maxVeinSize()` - a field authored under the pre-M11
`OrePlacementPlanner` as a literal hard cap on native-break cluster growth
(diamond=2, emerald/ancient_debris=1, coal/iron=3). Reused as a multiplier
with a floor of 5, every ore - rare or common - generated a vein of at least
6-9 blocks, up to 17. This is the measured cause of the owner's "too much ore,
too big veins, especially rare ones" playtest feedback.

## Fix

`OreField.Ore` now carries an explicit `sizeMin`/`sizeMax` (inclusive) instead
of a grade multiplier. `OreRarity.sizeFor(blockId)` (new,
`generation/OreRarity.java`) supplies that range per ore, ranked by combining
the catalog's own tier/weight signal with ordinary ore-rarity knowledge.

## Ranking and sourcing, per entry

| Band | Vein size | Ores | Why |
|---|---|---|---|
| Commodity | 5-10 | coal, copper, iron (+deepslate), nether quartz | Upper tier's highest `weight` entries; quartz is chaos-tier but is the *common* material within that tier (`weight=4`, the highest in its table) and is well-known as plentiful within its own biome in vanilla - treating it as "rare" because its tier is deep would be wrong. |
| Uncommon | 3-7 | zinc, sulfur, truffle dirt, redstone (+deepslate) | Mid `weight` within their tier; ordinary-but-lesser mining finds, not jackpots. |
| Rare | 2-5 | gold, lapis, nether gold, silver, seabrass (+deepslate) | Classic "worth a detour" ores by general rarity knowledge; modded equivalents (silver, seabrass) ranked alongside their closest vanilla analogue since the pack has no independent rarity audit for them. |
| Very rare | 1-3 | diamond, jewelry gem vein | Diamond is the standard "small, deliberate find" tier; jewelry gem veins already carry `max_vein_size=1` in the catalog, reinforcing the same tier. |
| Extremely rare | 1-2 | emerald, ancient debris, jewelry deepslate gem vein, dark metal deposit | The rarest possible finds by both general knowledge (emerald, ancient debris) and the catalog's own lowest `weight`/`max_vein_size` values; always a tiny, special moment. |

Unranked/future catalog entries default to a conservative 3-5 block range
rather than silently falling through to the old formula.

## What this does not claim

`weight` in `ore_catalog/standard.json` was never economy-validated (M3's own
report: "Weights are relative selection weights, not measured economy rates").
This ranking is a documented starting point for owner playtest, the same
caution M3 and M4b already apply to their own numbers - not a measured
balance guarantee.

## Effect on the existing exposed-ore-share design target

`Mine-Concept-v2.md` section 5 sets minimum exposed-ore-wall-share targets
(L1 >= 6%, L2 >= 10%, L3 >= 14%), which the pre-fix build exceeded (measured
10.75% / 12.9% / 15.0% via `/dawnmine census` on 2026-11-15) only because of
the sizing bug - every vein, including rare ones, was inflated. Shrinking
individual vein sizes to respect rarity necessarily lowers the overall
exposed-ore percentage; that is the intended effect of this fix, not a
regression. The concept doc's target line has been annotated accordingly
(see `docs/Mine-Concept-v2.md`); `OreFieldTest`'s synthetic fixture (which
never used real catalog data) is unaffected and still passes unchanged.
