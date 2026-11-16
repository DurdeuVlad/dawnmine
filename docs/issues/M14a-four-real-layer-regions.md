# M14a Issue Draft — Four real cave-layer regions (upper/middle/chaos/nether)

## Planning metadata
- Issue type: Bug / architecture foundation
- Priority: High
- Milestone: M14 — Per-layer customization and depth-based chaos escalation
- Assignee: Unassigned
- Target date: Not set

## Intent

The cave stack has four physical layers (`CavePlanner.LAYERS = 4`: upper, middle, chaos,
nether), but every data system that assigns ore tables, loot tables, and mob encounters to a
layer still only knows three region names (`upper`/`middle`/`chaos`) and silently reuses
`chaos`'s table for layer 3 via `Math.min(layer, N-1)` indexing. The nether layer was added in
M12e; nothing since has given it its own identity. This was the root cause the owner's "nether
mobs in the nether layer" request ran into directly: the layer doesn't currently *have* its own
mob roster to customize, because it isn't a real region yet.

## Expectation

The nether layer (layer index 3) is a fourth, first-class region end to end: its own ore table,
its own loot table, its own encounter compositions (mob roster), and its own encounter-site
density - none of them borrowed from chaos through an index-clamp. Every place in the codebase
that currently special-cases "layer 3 reuses chaos" is replaced with a real `"nether"` region.

## Context that code cannot infer

Confirmed by reading the code (file:line), every one of these reuses chaos for layer 3 via the
same `Math.min(layer, length-1)` pattern:
- `OreCatalog.Tier` enum (`OreCatalog.java:46-57`) has exactly three values (`UPPER`, `MIDDLE`,
  `CHAOS`); `parse()`/`isKnown()` reject anything else. `CaveTerrain.ores()` (per the M13
  session's own notes) builds per-layer ore tables as `tiers[Math.min(layer, tiers.length-1)]`.
- `LootSites.REGIONS = {"upper","middle","chaos"}` (`LootSites.java:27`), indexed the same way
  at site-planning time (`REGIONS[Math.min(layer, REGIONS.length - 1)]`, `LootSites.java:91`).
- `EncounterSites.REGIONS = {"upper","middle","chaos"}` (`EncounterSites.java:23`), indexed the
  same way (`REGIONS[Math.min(layer, 2)]` at `EncounterSites.java:64`, and again at
  `EncounterSites.java:75` for landmark-hook encounter sites). Its site density constants
  `CELL = 28` / `CELL_CHANCE = 0.75` (`EncounterSites.java:19-20`) are flat across all four
  layers - there is no per-layer density at all yet, let alone one that favours depth.
- `MineEncounterCatalog.parse()` (`MineEncounterCatalog.java:162-166`) hard-requires exactly
  `{"upper","middle","chaos"}` as region ids and throws if any is missing - a profile cannot even
  declare a `"nether"` region's compositions today without this check first accepting it.
- Only three loot table files exist (`data/dawnmine/loot_table/chests/{upper,middle,chaos}_crate.json`)
  - there is no `nether_crate.json`.
- `MineEncounterManager.regionAtY` (`MineEncounterManager.java:635-648`) is a *separate*,
  legacy v1-topology-based region lookup (keyed off `TopologyProfiles.active().regions()`, a
  different, hub-scoped system) that defaults to `"chaos"` for any Y outside its known ranges -
  this may or may not be reachable for nether-layer Y values in practice; the implementer should
  check whether this path is actually exercised by any nether-layer code and note the finding
  either way, rather than assume it is dead.
- Vanilla mobs already used in the `chaos` region's compositions (`encounters/standard.json`,
  `chaos_wither_patrol` and `chaos_ranged_blaze`) already include `wither_skeleton` and `blaze` -
  so a nether roster that reuses those exact entities would make chaos and nether feel
  identical. The owner asked for "nether mobs in the nether layer," which this issue should
  satisfy with a roster that reads as distinctly nether-themed without duplicating chaos's
  existing signature mobs outright.
- The owner's and this conversation's working default for the nether roster was zombified
  piglin, wither skeleton, magma cube, and blaze - explicitly not ghast, since the nether
  layer's own max open height (`CavePlanner.Spec.layer` case 3: `maxHeight=9`) is tight for a
  flying mob that wants more vertical room. Given the blaze/wither_skeleton overlap just found,
  the implementer should resolve this as an open decision (see below), not silently ship the
  overlap.

## Scope

- Add a fourth value to `OreCatalog.Tier` (e.g. `NETHER`, parsed as `"nether"`); update
  `isKnown()` accordingly. Add nether-tier entries to `ore_catalog/standard.json` (can reuse
  existing block ids with nether-appropriate selection/rarity, or introduce nether-flavoured
  ones such as `minecraft:ancient_debris`/`minecraft:nether_quartz_ore` moved or duplicated into
  this tier - implementer's call, consistent with M12a's existing rarity research).
- Add `"nether"` to `LootSites.REGIONS` and `EncounterSites.REGIONS`; remove the
  `Math.min(layer, length-1)` clamps in both once the arrays have four entries matching the four
  layers directly.
- Add a fourth loot table `data/dawnmine/loot_table/chests/nether_crate.json`, following the
  M13 session's "real ore drops and torches only" convention (see the existing three tables for
  the pattern) with nether-appropriate contents.
- Allow `MineEncounterCatalog.parse()` to accept a `"nether"` region (required alongside the
  existing three, or optional with a documented fallback - open decision below) and add a
  `"nether"` region with its own compositions to `encounters/standard.json`, using a roster
  resolved per the open decision on the blaze/wither_skeleton overlap.
- Resolve, document, and implement per-layer encounter-site density: at minimum, give
  `EncounterSites` (and ideally `LootSites`) distinct per-layer `CELL`/`CELL_CHANCE` values
  instead of one flat constant pair for all four layers, as the foundation M14b's density
  commands and M14c's depth escalation will both build on. The exact values are an open
  decision, not this issue's to invent from nothing - a reasonable starting point is "no looser
  than today's flat values for any layer," deferring actual escalation tuning to M14c.
- Investigate and report on `MineEncounterManager.regionAtY`'s reachability for nether-layer Y
  values; fix it only if it is actually reachable and wrong, otherwise record the finding.

## Non-goals

- The admin customization commands themselves (ore/mob/loot/density overrides, TOML scalars) -
  that is M14b, which depends on this issue existing first so there are four real layers to
  scope commands against.
- Systematic depth-based scaling of mob count/variety/level beyond giving nether its own
  compositions - that is M14c.
- Any change to the upper/middle/chaos ore, loot, or mob data beyond what's needed to make room
  for a fourth region (e.g. no re-balancing of existing tiers).
- Any change to `OreRarity.java`'s hardcoded vein-size map or its inconsistency with the
  catalog's unused `max_vein_size` field - real and worth fixing, but a separate, pre-existing
  issue not created by this work; flag it in the PR as a known follow-up, do not silently fix it
  as a drive-by here.

## Dependencies and open decisions

- Depends on M12a-M12g and the M13 live-feedback session (merged to `main` as of `v0.6.0`) for
  the current four-layer cave stack, ore density, and loot-table baseline this issue extends.
- Open decision: should `"nether"` be a *required* region in `MineEncounterCatalog.parse()`
  (matching the existing hard-fail-if-missing behaviour for upper/middle/chaos), or optional
  with an explicit fallback? Required is more consistent with the rest of this issue's "no more
  silent reuse" intent, but changes the profile schema's backward compatibility story for any
  other encounter profile besides `standard`. Recommend required, but the owner or implementer
  should confirm before writing the validator change.
- Open decision: how to avoid the nether roster overlapping chaos's existing `wither_skeleton`/
  `blaze` use. Options include dropping one or both from the nether default roster, keeping them
  in chaos but using different specific mobs in nether (e.g. zombified piglin, magma cube, plus
  a different ranged/elite choice), or accepting the overlap deliberately as a "deeper = you see
  this again, harder" signal. Needs an explicit decision in the PR, not a silent pick.
- Open decision: exact per-layer `CELL`/`CELL_CHANCE` (or equivalent) density values - deferred
  to this issue's scope only insofar as the *mechanism* must exist; the *tuning* is M14c's.
- Product owner: Durdeu Vlad-Ioan. Implementer: the next agent assigned this issue. Reviewer:
  independent.

## Acceptance criteria

1. `OreCatalog.Tier`, `LootSites.REGIONS`, and `EncounterSites.REGIONS` each have four values
   corresponding 1:1 to the four cave layers; no code path indexes any of them with
   `Math.min(layer, N-1)` or an equivalent clamp.
2. A nether-region ore table, loot table (`nether_crate.json`), and at least two distinct
   encounter compositions exist and are loadable (server boots and `/reload`s without any
   `MineEncounterCatalog`/`OreCatalog`/loot-table parse error).
3. A fresh `/dawnmine reset` followed by `/dawnmine census` shows nether-layer ore and loot
   sites drawn from the new nether tables specifically (not numerically identical to chaos's),
   and nether-layer encounter sites spawning the new nether composition's entities (verified via
   `/dawnmine census` route/band reporting and/or a GameTest asserting the spawned entity types
   at a nether-region site).
4. The blaze/wither_skeleton overlap open decision above is explicitly resolved and stated in
   the PR, not silently left as-is.
5. `MineEncounterManager.regionAtY`'s nether-layer reachability is investigated and the finding
   (fixed, or confirmed unreachable/not applicable) is recorded in the PR.
6. Existing `OreFieldTest`, `LootSitesTest`, `EncounterSitesTest`, `CaveStackTest`, and
   `MineEncounterCatalog`-adjacent tests that assumed three regions are updated to assert the
   fourth; none is left silently exercising only three layers the way the M13 session found
   `CaveStackTest.snapshot()` and `CensusService`'s bands had been doing for layer 3's own
   Y-range.

## Verification

`./gradlew test` full suite. A full `/dawnmine reset` + `/dawnmine census` cycle on the local
test server, specifically inspecting nether-layer ore/loot/mob output for nether-specific
content (not chaos's). Independent adversarial review of the region-model change, given M13's
session found three real bugs in adjacent code during its own live verification - this class of
change (touching catalog/region plumbing in several files at once) has already proven easy to
get subtly wrong.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals,
affected ownership, dependencies, commands/results, and residual risks - including an explicit
statement of how the blaze/wither_skeleton and `regionAtY` open decisions were resolved.
