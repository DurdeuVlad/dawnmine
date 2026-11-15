# M12d Issue Draft — Build the loot/POI system (chests, vaults, corpses)

## Planning metadata
- Issue type: Feature / world generation
- Priority: High
- Milestone: M12 — Owner playtest fixes: ore density, mob difficulty/drops, loot, layer connectivity, mob variety
- Assignee: Unassigned
- Target date: Not set

## Intent

Owner playtest confirmed there are no loot chests anywhere in the generated mine. This is a measured fact, not a feeling: the live `/dawnmine census` run on the current build reports `loot=0` across the entire 16,384-chunk scan, and the longest empty stretch along the rush route without a loot interest point is 5,931 steps — i.e. loot effectively does not exist today. This was always a planned stage (`Mine-Concept-v2.md` section 5 "Loot" and section 9's R3 "POI framework... encounters and loot") that simply hasn't shipped yet.

## Expectation

Players find loot — miner's lockers, supply crates, ore carts with chests, dead-miner corpses, and vaults — while exploring, at the density and rarity spread already specified in the approved concept doc. Loot is mine-native only (ores, mine-made ingots/blocks, coal, gems, mineral blocks, ordinary mining supplies) with no exotic treasure items, per the already-approved loot economy decision.

## Context that code cannot infer

- Live evidence from this playtest session's census run (`dawnmine-local-test`, 2026-11-15): `census scan: ... loot=0 landmarks=2467`; `longestEmptyStretch(... loot=5931 ...)`. Compare to the concept doc's own target: "≥ 1 loot spot per 40-60 blocks of route" — currently met 0% of the time.
- `Mine-Concept-v2.md` section 5 already specifies five loot tiers (common, uncommon, rare, epic, boss), sources (lockers, crates, ore carts, corpses with a lore note, vaults, secret rooms, mini-boss/boss drops), daily randomized placement, and "keyed" vaults (a key/lever on one side of the level opens a vault elsewhere). Section 10.5 fixes the loot *economy* (mine-native only, no exotic items) but the *placement system* itself was never built.
- `Landmarks.java`/`LandmarkCatalog` (from M9c, since generalized into M11-3's "nine procedural side-chamber landmarks") is the closest existing precedent for a reusable, catalog-driven placed-feature system — this issue's POI/loot framework should follow that same data-driven pattern (a catalog of placeable feature types with a footprint/placement rule) rather than inventing an unrelated mechanism, but POIs are a different, smaller-scoped concept than landmarks (section 4 of the concept doc describes "Districts layer: Poisson-disk placement of POIs... each POI owns its ore, mobs and loot") and should be implemented as its own system, informed by but not merged into the existing landmark code.
- Must stay fully procedural (code + block layouts), per AGENTS.md — no authored/prefab loot rooms, matching the existing project convention already established for landmarks and motifs.

## Scope

- Design and implement a minimal first version of the POI/loot framework: at minimum, 2-3 loot-source types from the concept doc's list (e.g. a supply crate, a miner's locker, a dead-miner corpse) with a simple five-tier rarity roll, placed along generated routes at a density that closes the measured gap (target: no 40-60 block route stretch without at least one loot spot, mirroring the concept doc's own target, down from the current reality of a single 5,931-step gap for the whole run).
- Loot table contents stay inside the already-approved mine-native-only boundary (ores/raw ores, mine-made ingots/blocks, coal, gems, mineral blocks, ordinary mining tier-appropriate supplies) — no new exotic items.
- Placement should scale rarity by depth/region the same way ore and mobs already do (common loot upper, rarer loot deeper), consistent with the concept doc's tiering.
- A full vault/key system and corpse lore notes are explicitly allowed to land as a fast-follow rather than block this issue's first ship — see Non-goals.

## Non-goals

This issue does not need to deliver the full five-tier system, keyed vaults, or corpse lore notes in one pass — a minimal, honest first version (even just common/uncommon crates and lockers) that actually produces non-zero loot density is the acceptance bar; the richer tiers and vault/key mechanic are an explicit, documented fast-follow, not silently dropped. No exotic/pack-specific treasure items (loot-economy boundary stays fixed). No change to combat-reward drops (that is M12c's scope, a different mechanism).

## Dependencies and open decisions

- Depends on M11's cave/route generation (`CaveStack`, `CaveTerrain`) for placement anchoring, and on the existing `Landmarks.java`/`LandmarkCatalog` pattern as a structural precedent, though POIs are their own system.
- Independent of M12a-M12c and M12e-M12f, but should be measured with the same `/dawnmine census` tool once shipped, since that's already how this gap was discovered and quantified.
- Open decision for the owner: which 2-3 loot-source types ship first (recommend: supply crate, miner's locker, dead-miner corpse — all simple, no puzzle/key dependency) and whether the full vault/key system is a separate follow-up issue (recommended) or folded in here.
- Product owner: Durdeu Vlad-Ioan. Implementer: the next agent assigned this issue. Reviewer: independent.

## Acceptance criteria

1. A fresh `/dawnmine census` run on a newly generated mine reports `loot` > 0, and the reported longest empty stretch without a loot interest point drops from the current ~5,931 steps to within the concept doc's own target range (≤ roughly 40-60 blocks along the measured rush route, same units the census already reports).
2. Loot contents across a sampled set of opened loot sources contain only mine-native items (ores, mine-made ingots/blocks, coal, gems, mineral blocks, ordinary supplies) — no exotic/pack-specific treasure item ever appears.
3. Loot rarity/quality visibly increases with depth/region, mirroring the ore and mob scaling already in place.
4. Loot sources are placed procedurally (no authored/prefab structure), consistent with AGENTS.md and the existing landmark/motif precedent.
5. Daily reset regenerates loot placement along with everything else (no stale loot persisting across runs, matching the existing daily-reset contract).

## Verification

Run `/dawnmine census` before and after this change on the same seed to show the measured loot-density gap closing (the before numbers are already captured in this issue's Context section as a baseline). Add unit/GameTest coverage for the new POI/loot catalog's placement density and content-boundary checks (no exotic items). Manual playtest by the owner confirming loot is actually found while exploring.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
