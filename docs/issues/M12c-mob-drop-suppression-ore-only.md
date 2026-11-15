# M12c Issue Draft — Mine mobs drop only level-appropriate ore, not vanilla loot

## Planning metadata
- Issue type: Feature / loot economy
- Priority: Medium
- Milestone: M12 — Owner playtest fixes: ore density, mob difficulty/drops, loot, layer connectivity, mob variety
- Assignee: Unassigned
- Target date: Not set
- Loot economy constraint (AGENTS.md / `Mine-Concept-v2.md` section 10.5): mine-native only — ores/raw ores, ingots-from-the-mine blocks, coal, gems, mineral blocks, ordinary mining supplies. No exotic/pack-specific treasure items. This issue's drops must stay inside that boundary.

## Intent

Owner playtest found mobs still drop their ordinary vanilla/Born in Chaos loot (bones, rotten flesh, arrows, etc.). The owner wants that replaced entirely: mine mobs should only drop ore, scaled to the mob's level/region, reinforcing "you go to the mine to mine" (the already-approved loot-economy pillar).

## Expectation

A mob killed inside the mine drops no vanilla/native loot at all. Instead it drops ore (raw ore items/blocks already in `ore_catalog`) appropriate to its region tier and level — a higher-level mob in a deeper region drops better/more ore than a low-level upper-region mob. This replaces, rather than stacks on top of, the existing bonus-ore-on-death system from M4b.

## Context that code cannot infer

- Current state (confirmed in code): `MineEncounterManager` has no `LivingDropsEvent` handling at all. The only death-triggered logic is `onTrackedMobDeath` → `awardMineReward`, which calls `MineRewardCatalog`/`MineRewardRules` to *add* a small bonus ore drop (per `mine_rewards/standard.json`, keyed by region, e.g. upper → raw iron at 20%, middle → raw silver at 10%, chaos → diamond at 2%) on top of whatever the mob's own vanilla loot table already drops. Nothing suppresses that native loot table today.
- This issue changes that relationship: native loot must be suppressed, and the ore-drop mechanism becomes the mob's *only* drop, not a bonus layered on top of unchanged vanilla drops. Whether M4b's existing `mine_rewards` chance/quantity model is reused as-is for this purpose, or a new per-level drop table is introduced, is an implementation decision — but it must respect the existing mine-native-only loot boundary already in force.
- Scaling by level (not just region) is new: M4b's `MineRewardRules` currently keys off region only. This issue should factor in the mob's `dawnmineLevel` tag (from M9a/M12b) so a level-9 chaos mob drops more/better ore than a level-1 chaos-tier equivalent, if chaos ever gets multiple levels represented — confirm this is desired granularity with the owner if it turns out region alone was intended.
- Must not affect the native infected-ore summon's drop behavior (M4a's explicit non-goal: "Existing Diamond Termite shard drops are unchanged and receive no second bonus") or any hostile entity outside the mine dimension.

## Scope

- Add a `LivingDropsEvent` handler in `MineEncounterManager` (or a new small class alongside it) scoped to mine-tracked mobs only (same `RUN_TAG`/`SOURCE_TAG` identification already used elsewhere in the class): cancel/clear the event's default drops for any mob carrying mine-origin tracking data.
- Replace the suppressed drops with an ore-item drop resolved from the mob's region (`REGION_TAG`) and level (`dawnmineLevel`), reusing `OreCatalog`/`ore_catalog` entries' native drop items so the result stays inside the mine-native loot boundary and inside the existing tool/drop-item catalog (no new items).
- Reconcile with M4b's existing bonus-ore-on-death system (`MineRewardCatalog`/`MineRewardRules`/`mine_rewards/standard.json`): either extend that system to be the sole drop source (cleanest, reuses an already-audited mechanism) or keep it separate and additionally suppress native drops — implementer's call, documented in the PR, but the two systems must not double-award.
- Leave the native infected-ore summon's drop path (`born_in_chaos_v1:diamond_termite`) and anything outside the mine dimension completely untouched.

## Non-goals

No exotic or pack-specific treasure drops (stays inside the already-approved mine-native-only loot boundary). No change to the native infected-ore summon's drops. No XP changes. No change to player death/inventory behavior. No claim that the new drop rates are balanced — that is owner playtest, same caveat M4b already carries.

## Dependencies and open decisions

- Depends on M4a/M4b (`MineEncounterManager`, `MineRewardCatalog`, `MineRewardRules`, `mine_rewards/standard.json`) and M9a/M12b (`dawnmineLevel` tag) for level-aware scaling.
- Independent of M12a, M12d, M12e, M12f, though it should land after M12b if level-based scaling (not just region) is the chosen granularity, so the level tag's final scaling story is settled first.
- Open decision: does the owner want drops scaled by level, by region only, or both? Flagging since M4b today only has region granularity — recommend region+level but confirm by playtest once shipped.
- Product owner: Durdeu Vlad-Ioan. Implementer: the next agent assigned this issue. Reviewer: independent.

## Acceptance criteria

1. A mine-tracked mob's death produces zero vanilla/native loot-table drops (verified by comparing drop contents before/after for the same entity type).
2. The same death produces an ore-item drop whose source block/item exists in the active `OreCatalog` for the mob's region tier.
3. A higher-level mob's ore drop is measurably better (higher quantity, or a rarer ore entry, or both) than a lower-level mob in the same region, per whatever scaling granularity the implementer and owner settle on.
4. `born_in_chaos_v1:diamond_termite` native summon drops are unaffected (still unchanged, still no second bonus — re-verifies M4a's existing non-goal).
5. Any hostile entity killed outside `MineManager.MINE_DIMENSION` keeps its ordinary native drops entirely.
6. No double-award: a mine mob death produces exactly the intended ore drop once, not both a suppressed-then-replaced drop and a separate M4b bonus roll, unless the implementer's documented design explicitly intends both and the owner confirms that is wanted.

## Verification

Add/extend dedicated-server GameTest coverage (new or extended file alongside `MineEncounterManager`'s existing tests) killing a mine-tracked mob of each region tier and level band, asserting no vanilla drops and the correct ore-drop scaling. Manual playtest by the owner confirming drops feel right across a mining session.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
