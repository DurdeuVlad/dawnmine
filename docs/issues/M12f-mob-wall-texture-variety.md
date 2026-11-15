# M12f Issue Draft — More wall texture, more mob variety, and legible leveled nametags

## Planning metadata
- Issue type: Feature / content variety and presentation
- Priority: Medium
- Milestone: M12 — Owner playtest fixes: ore density, mob difficulty/drops, loot, layer connectivity, mob variety
- Assignee: Unassigned
- Target date: Not set

## Intent

Owner playtest feedback: the walls still need more texture/variety, the mob roster needs more vanilla and modded mobs (including more ranged options), and the level nametag should be more legible/expressive — colored and with effects — not plain white text.

## Expectation

Cave walls show noticeably more structural/decorative variety than today. Encounters draw from a wider roster of vanilla and modded hostile mobs, including ranged attackers at every depth tier, not just upper. A leveled mob's nametag is visually distinct by level (color, and optionally a small effect), not plain default text.

## Context that code cannot infer

- Wall texture already has a real mechanism (M9d's motif system, confirmed still loaded on this test server: `[dawnmine] Loaded 3 motif rule set(s): abandoned_cart, rubble_vein, collapsed_support`). This issue is about *expanding* that existing, working system with more motif types, not building a new one. M9d's own issue explicitly scoped itself to "3 motif types... to prove the architecture," with "more motifs are a straightforward follow-up" already anticipated.
- Mob roster already has some variety post-M11-4 (confirmed in `encounters/standard.json`): vanilla zombies/skeletons/cave spiders/spiders/vindicators/wither skeletons alongside Born in Chaos `decrepit_skeleton`/`bone_imp`, and at least one existing ranged composition (`upper_ranged_harasser`, a plain `minecraft:skeleton`). The owner wants this widened further, with ranged options at every tier (middle/chaos currently skew melee-heavy) and more modded mob sources considered.
- Nametag styling is currently plain: `MobLevelScaling.apply` calls `Component.literal("Lv. " + level + " " + name)` with no color/formatting. Minecraft `Component`/`Style` supports per-level color (e.g. `ChatFormatting`) cheaply; "effects" likely means either styled text (bold/color) or a particle/visual cue tied to level — confirm which with the owner before committing to a specific mechanism, since "effects" is ambiguous between text styling and an in-world visual.
- "Add buffs, not just health but speed and hit" for mob scaling is M12b's scope (attribute modifiers). If the owner separately means visible status-effect buffs (e.g. a `MobEffectInstance` like Strength/Speed applied at spawn, which also shows the standard potion-swirl particle as a readability cue), that is a distinct, additional mechanism from M12b's attribute modifiers and should be confirmed as in- or out-of-scope here rather than assumed.

## Scope

- Add additional motif rule sets to `MotifCatalog`/`data/dawnmine/motifs/*.json` beyond the existing 3 (collapsed_support, rubble_vein, abandoned_cart), following the exact same parametric-rule-set pattern (never a fixed grid, per M9d's own established constraint) — owner to help direct the visual themes, optionally with AI concept art per the earlier planning conversation's established pipeline.
- Expand `encounters/standard.json` with additional vanilla and modded hostile entity options, specifically adding ranged attacker compositions to middle and chaos tiers (not just upper), and broadening melee variety where the roster is currently thin. Any new modded entity reference must pass the project's existing fail-closed registry validation (`MineEncounterManager.validateEntityRegistry`-style check) before shipping.
- Add per-level nametag color (e.g. a simple color ramp: white/gray for low levels, yellow/orange for mid, red for high) to `MobLevelScaling.apply`, using `Component`/`Style` formatting. If the owner also wants an in-world visual effect (particle, glow outline), scope and confirm that addition explicitly — don't assume it silently.

## Non-goals

No new texture/resource pack (stays within the existing procedural-motif/vanilla-block convention, consistent with AGENTS.md and the earlier planning conversation's explicit rejection of a texture-pack pipeline). No change to attribute-based difficulty scaling numbers (that's M12b). No new entity classes — only existing vanilla/modded entity types already available in the target pack's registry.

## Dependencies and open decisions

- Builds on M9d (`MotifCatalog`/`MotifPlanner`) for wall variety and M9a/M12b (`MobLevelScaling`) for nametag styling — must not regress either's existing acceptance criteria.
- Independent of M12a, M12c-M12e.
- Open decision: does "effects" on the nametag mean text styling only (color/bold), or also an in-world visual cue (particles/glow)? Recommend starting with color-only text styling as the fast, low-risk version, with a visual effect as an explicit follow-up if the owner wants more after seeing it.
- Open decision: does "add buffs" here mean anything beyond M12b's attribute scaling (i.e. actual status-effect buffs with potion-swirl particles)? Flagging so it isn't silently merged into or split from M12b without the owner's input.
- Product owner: Durdeu Vlad-Ioan. Implementer: the next agent assigned this issue. Reviewer: independent.

## Acceptance criteria

1. At least 3 additional motif rule sets exist beyond the current 3, each generating varied, non-repeating instances per the existing M9d determinism/clearance-safety guarantees.
2. Middle and chaos tiers each gain at least one ranged-attacker composition (currently absent outside `upper_ranged_harasser`).
3. At least a handful of new vanilla and/or modded hostile entity types are added to the roster across tiers, each passing the existing fail-closed registry validation.
4. A leveled mob's nametag visibly changes color across the level range (e.g. distinguishable at a glance between a low-level and high-level mob of the same type).
5. No regression to M9a/M12b's existing nametag/level-tag/attribute behavior, or to M9d's motif clearance-safety guarantees.

## Verification

Extend `MotifCatalog`/`MotifPlacementGameTests`-style coverage for the new motif types. Extend `MineEncounterCatalog` parser tests and registry validation for the new roster entries. Add a simple assertion/test on `MobLevelScaling`'s nametag `Component` styling per level band. Manual playtest by the owner across a full depth descent, confirming wall variety, mob variety (including ranged encounters at depth), and nametag legibility.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
