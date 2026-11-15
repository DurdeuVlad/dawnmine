# M12a Issue Draft — Ore vein sizes must respect ore rarity

## Planning metadata
- Issue type: Bug / resource balance
- Priority: High
- Milestone: M12 — Owner playtest fixes: ore density, mob difficulty/drops, loot, layer connectivity, mob variety
- Assignee: Unassigned
- Target date: Not set

## Intent

Owner playtest of the M11 "Mine v2" cave rewrite found far too much ore, with veins far too large — including for the rarest ores (diamond). This is a confirmed regression, not a tuning nitpick: `OreField.grow` computes vein size from a catalog field that was never meant to be read that way, so every ore (common or rare) currently gets a vein of at least 6-9 blocks, up to 17.

## Expectation

Every ore's vein size on generation reflects its actual rarity: the rarest ores (ancient debris, emerald, diamond, jewelry gem veins) generate as small, special finds (a handful of blocks); common/early ores (coal, copper, iron) generate as the larger, reliable veins players expect to mine through. No ore type's typical vein size is decided by an unintended multiplier bug.

## Context that code cannot infer

- Root cause, confirmed by reading the code: `OreField.java:185` computes `int size = 5 + ore.grade() * (1 + rng.nextInt(4));` where `grade` (`OreField.Ore.grade()`) is populated directly from `OreCatalog.Entry.maxVeinSize()` (see `CaveTerrain.java:127`: `new OreField.Ore(e.blockId().toString(), e.weight(), e.maxVeinSize())`). Under the old (pre-M11) `OrePlacementPlanner`, `maxVeinSize` was a literal hard cap on cluster growth (diamond=2, emerald/ancient_debris=1, coal/iron=3 — see `ore_catalog/standard.json`). The new formula instead *multiplies* by that value and adds a floor of 5, so even the smallest catalog value (1) still produces a 6-9 block vein, and the largest (3) can reach 17.
- M3's existing `ore_catalog/standard.json` already carries two rarity-adjacent signals per entry: `weight` (relative selection frequency within its tier) and `tier` (upper/middle/chaos, itself a depth-rarity signal). M3's own report (`docs/reports/M3-ore-catalog.md`) states explicitly: "Weights are relative selection weights, not measured economy rates; this report makes no balance claim" — so `weight` is a reasonable starting rarity signal but was never economy-validated, and this issue should not overstate it as one.
- Owner's explicit direction for this issue (this planning conversation): "research how rare every ore is and respect that," rather than the implementer inventing arbitrary numbers. The intended rarity ranking should combine: (a) the existing per-entry `tier`/`weight` already set by the pack's own ore ecology design, and (b) well-established rarity knowledge for the vanilla-equivalent ores (coal > copper/iron > gold/lapis/redstone > diamond > emerald > ancient debris, in roughly increasing rarity) as a sanity check, since modded ores (zinc, sulfur, silver, seabrass, dark metal, jewelry gems) have no equivalent external reference and must be ranked from their existing `tier`+`weight` alone.
- All 32 catalog entries (30 native resources + 2 encounter ores) must receive an explicit, reasoned vein-size target — no entry should fall through to a default.

## Scope

- Add a documented rarity-to-vein-size mapping (e.g. a small ranked table or a formula keyed off `tier` + `weight`, not off `maxVeinSize` reused as a multiplier) and apply it either by introducing a new, correctly-named field (e.g. `targetVeinSize` or `veinSizeMin`/`veinSizeMax`) on `OreCatalog.Entry`/`ore_catalog/standard.json`, or by deriving the range purely from `tier`+`weight` in `CaveTerrain`/`OreField` without a schema change — implementer's call, but the result must be a direct, bounded cap per ore, not a multiplier with a large floor.
- Fix `OreField.grow`'s size calculation so it no longer adds a floor of 5 blocks to every ore regardless of rarity.
- Record the rarity research (the ranking and its sourcing/reasoning per ore) in a short report under `docs/reports/`, the same way M3 documented its own ore work, so the numbers are traceable and not a silent guess.
- Re-tune `ore_catalog/standard.json` entries only if the implementer's chosen mechanism requires new/changed fields; do not change `weight`, `tier`, native drop items, or tool requirements, which are out of scope and already audited by M3.

## Non-goals

No new ore blocks, no changes to tool requirements or native drops (M3's scope), no change to *how many* veins appear per segment (that is M11-2b's existing density system, not rarity-of-size), no claim of validated game economy balance — this issue fixes a sizing bug and grounds the fix in researched rarity, it does not certify the result as balanced without further owner playtest.

## Dependencies and open decisions

- Depends on M3 (`OreCatalog`, `ore_catalog/standard.json`) and M11-2b (`OreField`, `CaveTerrain`).
- Independent of M12b–M12f.
- Open decision already resolved by the owner in this planning conversation: use researched rarity (existing catalog `tier`/`weight` plus well-known rarity knowledge), not owner-dictated exact numbers and not implementer-invented numbers. The implementer still needs to turn that into one concrete table — record the reasoning in the new report so it can be challenged on evidence, not guessed again.
- Product owner: Durdeu Vlad-Ioan. Implementer: the next agent assigned this issue. Reviewer: independent.

## Acceptance criteria

1. The rarest catalog entries (ancient debris, emerald, deepslate emerald, both jewelry gem veins) generate veins no larger than a small, explicitly justified cap (a handful of blocks) — never defaulting to the old 6-9 block floor.
2. Mid-rarity entries (diamond, gold, lapis, redstone, silver, dark metal deposit) generate visibly smaller veins than common entries, and visibly larger than the rarest tier.
3. Common, early-tier entries (coal, copper, iron, zinc, sulfur) generate the largest veins in the catalog, within a bounded, documented range.
4. No ore's effective vein size is still derived from the `5 + grade*(1+rand(4))` formula or any other unintended multiplier-with-floor pattern.
5. A written rationale exists (new `docs/reports/` entry) naming the rarity ranking used for every one of the 32 catalog entries and its sourcing (existing tier/weight vs. general rarity knowledge), so the numbers are auditable.
6. Existing `OreFieldTest`/`CavePlannerTest` coverage is updated to assert the new size bounds per rarity tier, not just that *a* vein was placed.

## Verification

Unit tests on `OreField` asserting vein size bounds per rarity tier using fixed seeds/fixtures (extend `OreFieldTest.java`). Dedicated-server GameTest or census-style sampling (reusing the `/dawnmine census` ore-exposure reporting already built in M11-0) comparing exposed-ore block counts per ore type across several generated runs, confirmed against the documented rarity ranking. Manual playtest by the owner on a generated seed, specifically checking that finding a diamond vein feels like a small, special moment and common ore still reads as abundant.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
