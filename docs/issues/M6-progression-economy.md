# M6 Issue Draft — Durable equipment and depth progression economy

## Planning metadata

- Issue type: Gameplay progression / economy
- Priority: Highest gameplay priority; this closes the loop between daily resets and returning deeper
- Milestone: M6 — Durable equipment and depth progression economy
- Assignee: Devin session; no GitHub account assignment without verification
- Target date: Not set

## Intent

Make every expedition's mining, PvE, and exploration contribute to useful lasting capability. Players should leave or bank finds, build better equipment, and use it to push deeper on the next run. Each upgrade should solve a real earlier-depth problem while deeper regions add a different problem that still asks for skill and preparation.

## Expectation

A reproducible progression graph maps real Rustic Craft 2 items, recipes, tools, armor, ore sources, and bounded mine rewards into a durable upgrade path. Playtests show an upgrade changing what the group can safely handle and enabling a more ambitious attempt. Gear helps; it does not skip the daily expedition or trivialize new encounter mechanics.

## Context that code cannot infer

- Terrain/run seed, mobs, loose drops, and run-local checkpoints reset each configured morning. Player equipment/materials and private Dispatch contents persist.
- The pack audit must establish actual items, recipes, and ore IDs. User examples (iron/steel, silver, diamond) express intended capability progression; they are not verified pack recipe facts.
- The experience rewards mining, PvE, exploration, preparation, fast decisions, and later team coordination. Difficulty cannot be only larger mob health/damage.
- Keep vanilla death/inventory behavior for v1. No extra mod-specific death penalty.
- Netherrack is off by default and is not a required progression material; optional server config limits it to very rare extreme-depth generation.
- See `Progression.md` for the full gameplay/evaluation contract.

## Scope

- Build a data-backed inventory of real pack item/recipe/equipment upgrade paths from M0 and modpack sources. Record gaps, pack-version/hash, and unsupported/uncertain recipes; never infer an item from its name.
- Map mine ores, combat rewards, and exploration finds to upgrade inputs. Every intended progression material has a repeatable, bounded source and a sink/value. Protect server economy by documenting expected yield per run/depth.
- Define successive capability steps from the actual pack: for each, name the old problem addressed (for example, an identified shallow mob threat, tool requirement, or hazard), the equipment/recipe/material, acquisition route(s), and the next new counter/challenge.
- Prefer existing pack equipment and recipes. If the audited pack lacks an item/recipe to create a necessary gameplay counter, add only the smallest cohesive server-safe mine progression equipment/recipe set, with registry, recipe, client synchronization, and compatibility tests. Do not add speculative gear trees.
- Keep equipment/player materials outside mine-owned reset state. Ensure ore/reward tables and recipes cannot be endlessly duped by reset/restart.
- Document progression visibility: how players discover available upgrades and what depth/risk each upgrade helps them face.

## Non-goals

- No permanent depth unlock that bypasses a fresh daily expedition or gives a private instance.
- No automatic item grants, pay-to-skip, economy currency, arbitrary pack-wide recipe changes, global loot changes, or custom death penalty.
- No promise that solo players can reliably clear the deepest intended tier; design that tier around coordinated group play and prove it.
- No Netherrack requirement or default generation.

## Dependencies and open decisions

- Depends on M0 verified pack recipes/items/equipment, M3 ore tiers, M4 mine-only rewards, and M5 banked persistent materials.
- M8 playtests determine actual yields and whether a capability step solves the intended problem without flattening the next tier.
- If pack artifacts are unavailable or recipe behavior is unclear, record a blocked node and use only verified alternatives; do not invent recipes silently.
- Owner: product owner. Implementer: Devin. Independent Flux reviewer required.

## Acceptance criteria

1. A versioned progression graph covers every configured progression item/material with its source (mining/PvE/exploration), recipe/cost, output, intended depth, and upgrade counter. Every pack claim is supported by artifact/recipe evidence.
2. Each upgrade step names a specific earlier challenge it improves and a different later challenge it does not solve; the resulting path preserves tactical decisions rather than becoming a pure gear check.
3. Mining, PvE, and exploration each contribute useful progression inputs or a tested equivalent team contribution. No single activity is an unavoidable grind gate if a viable team route can provide that need.
4. A repeat-run dedicated-server playtest verifies a group banks materials, crafts/equips an upgrade outside run-local state, returns in a later daily run, and can make a deeper attempt because of the upgrade.
5. The same playtest records at least one new challenge that requires route/tactical/team adaptation after the earlier problem is reduced.
6. Equipment/material progression survives reset, restart, and relog; mine rewards/material recipes cannot duplicate through death-event retry, reset, or restart.
7. No custom death loss is introduced; native pack recipes/loot remain unchanged except explicitly namespaced new mine recipes/items.
8. Tests cover recipe/material mapping, yield bounds, persistence across reset, duplication/retry cases, and fallback when an audited item is missing.

## Verification

Capture the progression graph with pack artifact/version references, actual recipe outputs, and yields by route/depth. Run at least two sequential daily runs on a dedicated server; record starting gear, routes, banked inventory, recipe/craft, changed capability, new counter, group size, and outcome. Include solo/group play at the intended deep-tier boundary as part of M8.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
