# M12b Issue Draft — Mob difficulty must scale hard across levels

## Planning metadata
- Issue type: Feature / combat balance
- Priority: High
- Milestone: M12 — Owner playtest fixes: ore density, mob difficulty/drops, loot, layer connectivity, mob variety
- Assignee: Unassigned
- Target date: Not set

## Intent

Owner playtest found level 7 mobs don't feel meaningfully different from level 1. M9a (`MobLevelScaling`) deliberately capped scaling to health only, at a maximum +60%, specifically to stay cautious. The owner now explicitly wants that reopened: a hard, large difficulty curve using more than just health.

## Expectation

A level 9 mob is a clearly, dramatically harder fight than a level 1 mob of the same base type — noticeably tougher, hits harder, and is faster — not just a bigger health bar. The curve should feel like a real difficulty ramp across the full 1-9 range, not a soft cap.

## Context that code cannot infer

- Current state (confirmed in code): `MobLevelScaling.java` caps health bonus at three bands (+15% at 3-5, +35% at 6-8, +60% at 9+) and explicitly never touches damage or speed — its own class doc says "never damage, never speed." That caution is exactly what the owner is now asking to remove.
- Owner's explicit direction (this planning conversation): "make them scale difficulty hard," confirmed via clarifying question as "Health + damage + speed, large curve."
- AGENTS.md constraint still applies: Born in Chaos's global attributes/AI outside the mine dimension must not change. All scaling stays applied at mine-spawn time via `AttributeModifier`s, the same mechanism M9a already uses, never a global datapack/entity-type edit.
- M9a's existing acceptance criteria (level-1 mobs stay unmodified native stats, native infected-ore summons never get leveled) are still the baseline this issue builds on, not replaces.

## Scope

- Extend `MobLevelScaling.apply` to also add bounded `AttributeModifier`s for `Attributes.ATTACK_DAMAGE` and `Attributes.MOVEMENT_SPEED` (in addition to the existing `MAX_HEALTH` modifier), using the same `ADD_MULTIPLIED_BASE` operation pattern and the same per-level-band structure already established.
- Replace the current three soft bands with a wider, harder curve across the full level range. A level 1-2 mob keeps unmodified native stats (unchanged from M9a); levels 3 upward should ramp meaningfully rather than plateauing at +60%. Implementer proposes concrete numbers as part of the PR, since the owner asked for "large" rather than dictating exact multipliers; record the reasoning.
- Keep per-attribute modifier IDs namespaced and permanent exactly like the existing `HEALTH_MODIFIER_ID` pattern (new `DAMAGE_MODIFIER_ID`, `SPEED_MODIFIER_ID` constants), so re-applying `apply` to an already-leveled mob (e.g. on chunk reload) stays idempotent via the existing `hasModifier` guard pattern.
- Keep the nametag/level-tag behavior and the native infected-ore summon exclusion exactly as M9a left them — this issue only changes *what* scales, not who gets scaled or how it's displayed (nametag color/effects belong to M12f).

## Non-goals

No new entity classes or AI behavior changes (that remains a separate, larger idea raised in earlier planning, not part of this issue). No change to the native infected-ore summon path. No global Born in Chaos datapack/registry edits. No claim of validated PvE balance beyond the owner's own playtest — "hard" is the owner's explicit direction, but the exact curve still needs the owner's in-game confirmation once implemented.

## Dependencies and open decisions

- Builds directly on M9a's `MobLevelScaling`/`MineEncounterManager` integration; must not regress M9a's existing acceptance criteria (level-1 unmodified, native summon exclusion, fail-closed catalog validation).
- Independent of M12a, M12c-M12f.
- Open decision: exact multiplier curve per band. The owner confirmed direction ("large curve, health+damage+speed") but not exact numbers — implementer proposes and documents a concrete table (e.g. in the class doc, the way the existing bands are documented), and the owner validates by playtest after this ships.
- Product owner: Durdeu Vlad-Ioan. Implementer: the next agent assigned this issue. Reviewer: independent.

## Acceptance criteria

1. A level 1-2 mob has unmodified native `MAX_HEALTH`, `ATTACK_DAMAGE`, and `MOVEMENT_SPEED` (no regression from M9a's existing guarantee).
2. A level 9 mob's resolved `MAX_HEALTH`, `ATTACK_DAMAGE`, and `MOVEMENT_SPEED` are all measurably higher than a level 1 mob of the same base entity type, each proven by a test reading the resolved attribute post-spawn.
3. The difference between level 1 and level 9 is large enough to be obviously distinguishable in a timed test fight (e.g. time-to-kill and damage-taken-per-hit both differ by a wide, documented margin) — not a marginal percentage.
4. Re-applying `MobLevelScaling.apply` to the same mob instance twice does not stack modifiers (idempotency preserved for all three attributes, matching the existing `MAX_HEALTH` guard).
5. `born_in_chaos_v1:diamond_termite` native summons still receive no level tag, nametag, or attribute modification (M9a's exclusion unchanged).
6. No change to Born in Chaos entity attributes/AI is observable outside `MineManager.MINE_DIMENSION`.

## Verification

Extend the existing M9a GameTest coverage (`MobLevelGameTests.java`) with damage/speed assertions per level band, and add a timed mock-fight test comparing level 1 vs. level 9 outcomes if the project's test harness supports simulated combat (otherwise rely on resolved-attribute assertions plus manual playtest). Manual playtest by the owner comparing a level 1 and a level 9 encounter of the same mob type back to back.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
