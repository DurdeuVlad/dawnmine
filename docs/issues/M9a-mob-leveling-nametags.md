# M9a Issue Draft — Depth-scaled mob levels and readable nametags

## Planning metadata
- Issue type: Feature / combat encounters
- Priority: High
- Milestone: M9 — Adventure revival (M9a leveling, M9b rare ore moments, M9c landmark safehouses, M9d structure motif pass)
- Assignee: Unassigned
- Target date: Not set

## Intent

The owner reported the mine no longer feels like an adventure and specifically asked for "monsters that have levels, level 1 being easy and you can read their nametag." Depth already has region tiers (upper/middle/chaos) and the project's own stated gameplay pillar already requires "different readable tactical counters" per depth, not just bigger numbers. This issue makes that readable at the mob itself, not only in the composition's internal `threat`/`counterplay` text.

## Expectation

Every hostile mob spawned through the mine's scheduled encounter system shows a visible nametag of the form `Lv. <N> <Name>`, where `N` is read from its composition's configured level and increases with topology depth. A level-1 encounter is observably the easiest fight in the mine (unmodified native stats); higher levels apply a small, capped health-only scaling on top of the existing Born in Chaos entity roster. Nothing about Born in Chaos entities outside the mine dimension changes.

## Context that code cannot infer

- Hard constraint (AGENTS.md): do not change Born in Chaos's global spawn, AI, or loot behavior for entities outside the mine.
- Region bands are already fixed and validated: `upper` → `middle` → `chaos`, shallowest first (`TopologyProfile.validate()`).
- `encounters/standard.json` compositions already carry hand-written `threat`/`counterplay` text per composition (e.g. `upper_lone_pursuer`, `chaos_imp_surge`). The level is a new, separate field that supplements this text; it must not require rewriting the existing threat/counterplay copy.
- `MineEncounterManager`'s spawn loop (the `for` loop that creates mobs, roughly lines 109–134 of `MineEncounterManager.java`) is the *only* place mine-scheduled mobs are created; it already writes `RUN_TAG`, `SOURCE_TAG`, and `REGION_TAG` into each mob's persistent data right after `finalizeSpawn`/`setPersistenceRequired` — the new level tag/nametag must be applied at that exact point, for consistency and because later code depends on those tags already being present.
- A second, unrelated mob path exists: native infected-ore summons (`born_in_chaos_v1:diamond_termite`, triggered by breaking `born_in_chaos_v1:infected_diamond_ore`/`infected_deepslate_diamond_ore` per `ore_catalog/standard.json`). M4a's accepted issue explicitly states this native path receives **no custom attribute scaling**. This issue must not reopen that scope — the native summon stays untouched.
- M4a's existing acceptance criteria (population caps, safe-zone exclusion, run cleanup/UUID tracking, fail-closed registry validation) are already shipped and must not regress.

## Scope

- Add a `level` integer field to `MineEncounterCatalog.Composition` and parse/validate it in `MineEncounterCatalog.parse` the same way `weight`/`threat`/`counterplay` are already validated (required, bounded range, reject missing/out-of-range the same way `integer(json, key, min, max)` already does for `weight`).
- Update `encounters/standard.json` to add an explicit `level` to every existing composition. Do not infer a level from the region name in code — it must be an explicit per-composition datapack value, matching the project's existing convention of never guessing a tunable number (see M3's "do not invent high rates" precedent). Suggested starting values, not a locked balance claim: `upper_lone_pursuer`=1, `upper_two_pursuers`=2, `middle_melee_pair`=4, `middle_two_imp_push`=5, `chaos_imp_and_pursuers`=7, `chaos_imp_surge`=8.
- In `MineEncounterManager`'s spawn loop, immediately after the existing `mob.getPersistentData().putString(REGION_TAG, ...)` line: write a new `dawnmineLevel` integer tag to the mob's persistent data; call `mob.setCustomName(Component.literal("Lv. " + level + " " + mob.getType().getDescription().getString()))`; call `mob.setCustomNameVisible(true)`.
- Add a bounded, data-independent health-scaling table keyed by level band (e.g. levels 1–2: +0%, 3–5: +15%, 6–8: +35%, 9+: +60%, hard-capped) applied via an `AttributeModifier` on `Attributes.MAX_HEALTH` only — never damage, never speed — at the same spawn point, before `addFreshEntity`. The multiplier curve lives as named constants in `MineEncounterManager` (or a small new helper class), not inline magic numbers, so it can be tuned without touching the spawn loop logic.
- Explicitly skip the native infected-ore summon path: the `EntityJoinLevelEvent` handler (`onEntityJoinMine`) that tracks that path must not receive any of this issue's nametag/attribute logic.

## Non-goals

No new entity classes or new mob types. No behavior-profile variety per level (different AI/attack patterns per depth is a larger, separate idea raised in planning and not part of this issue). No changes to the native infected-ore summon mob. No global Born in Chaos datapack/registry edits. No damage-attribute scaling. No HUD/UI additions beyond the vanilla entity nametag. No balance claim beyond "level 1 is unmodified and the easiest fight."

## Dependencies and open decisions

- Independent of M9b, M9c, and M9d; safe to implement and ship first.
- Builds directly on M4a's existing `MineEncounterCatalog`/`MineEncounterManager` and must not regress any of M4a's already-accepted acceptance criteria.
- Open decision for the product owner: the exact per-composition `level` values and the exact health-scaling curve above are proposed defaults, not an approved balance target — tune from playtest evidence, the same caution M3/M4a already apply to their own numbers.
- Product owner: Durdeu Vlad-Ioan (this planning conversation). Implementer: the next agent assigned this issue. Reviewer: independent, per AGENTS.md's fresh-reviewer requirement.

## Acceptance criteria

1. Every mob spawned through `MineEncounterManager`'s scheduled-composition path carries a visible custom name of the form `Lv. <N> <Name>`, where `N` exactly matches its composition's configured `level`.
2. A level-1 (or any level in the "no scaling" band) composition's mobs have unmodified native `MAX_HEALTH` — no `AttributeModifier` applied.
3. Attribute scaling, where applied, never exceeds the configured cap; a test reads the mob's resolved `MAX_HEALTH` attribute post-spawn and compares it against the expected table value.
4. `born_in_chaos_v1:diamond_termite` native summons never receive a `dawnmineLevel` tag, a custom name, or an attribute modification.
5. `MineEncounterCatalog` parser tests reject a composition missing `level` or with a `level` outside the defined valid range, mirroring the existing rejection tests for missing/invalid `weight`/`threat`/`counterplay`.
6. Diffing Born in Chaos entity attributes/AI for naturally spawned entities outside `MineManager.MINE_DIMENSION`, taken before and after this change, shows no difference.
7. All of M4a's existing acceptance criteria (population caps, safe-zone exclusion, run cleanup, fail-closed registry validation) still pass unmodified.

## Verification

Extend the existing `MineEncounterCatalog` parser test suite with new `level`-field cases (missing, out-of-range, valid, per-region defaults). Add a dedicated-server GameTest file `MobLevelGameTests.java` in `com.dwurdy.dawnmine.gametest` (`@GameTestHolder("dawnmine")`, matching the existing style in `OreBreakGameTests.java`) that drives `MineEncounterManager.onNodeGenerated` against a fixture node per region and asserts the spawned mob's custom name, custom-name-visible flag, and resolved `MAX_HEALTH` match the configured table. Manually verify on a running dev server that a level-1 nametag is legible at normal render distance and that triggering a native infected-ore encounter produces a mob with no nametag/level tag. Re-run the full existing M4a GameTest suite to confirm no regression.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
