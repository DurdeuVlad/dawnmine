# M14c Issue Draft — Depth-based escalation: deeper means more and harder

## Planning metadata
- Issue type: Feature / design-needed
- Priority: Medium
- Milestone: M14 — Per-layer customization and depth-based chaos escalation
- Assignee: Unassigned
- Target date: Not set
- Status: `needs-input` - this issue intentionally does not specify a single mechanism; see
  Dependencies and open decisions.

## Intent

The owner wants the mine to feel progressively more chaotic with depth: "as deeper as you go in
the cave, the more chaotic the situation becomes. more mobs, different types, higher level,
harder mobs." This is a felt-experience goal stated directly by the owner in this planning
conversation, not yet translated into a concrete mechanism or acceptance bar - this issue exists
to make that translation, not to assume it has already happened.

## Expectation

A player descending from upper to nether notices, without needing to be told, that each layer
feels meaningfully more dangerous and more crowded than the one above it: more mobs present at
once, a wider variety of mob types encountered, a visible bias toward higher-level (harder)
compositions, and no sense that "chaos" and "nether" (or any two adjacent layers) are
interchangeable in difficulty.

## Context that code cannot infer

- Some of this escalation already exists, hand-authored, in `encounters/standard.json`: composition
  `level` already climbs from 1-2 (upper) to 4-5 (middle) to 7-8 (chaos), and population per
  composition already grows loosely (upper tops out at 2, chaos's `chaos_imp_surge` reaches 4) -
  but this is manual authoring per composition, not a systematic rule, and there is no nether
  region yet for it to continue into (see M14a).
- `MineEncounterCatalog.Region.populationCap()` (currently a flat 200 per region, `totalPopulationCap`
  600 - `encounters/standard.json`) is a *concurrent, run-wide* budget per region, not a per-site
  multiplier - it limits how many mobs from that region can be alive at once across the whole
  run, not how many spawn at one encounter. "More mobs per encounter" as the owner describes it
  is really about `Composition.population()` (the entities+bonusEntities count in a single
  chosen composition) trending upward with depth, and/or encounter-site density
  (`EncounterSites.CELL`/`CELL_CHANCE`, currently flat across all layers per M14a's findings)
  trending upward with depth so encounters are simply more frequent underfoot.
- `MobLevelScaling` (stat multipliers for a given level number) and the mob *roster*/*composition
  selection* (which entities, how many, how often) are two separate axes that both need to move
  together for the owner's described feel: a mob that is merely statistically harder at the same
  encounter frequency does not read as "more chaotic," only as "the same fights take longer."
- M14b's `difficulty` and `density` admin commands, once they exist, are natural knobs for an
  admin to *tune* whatever escalation curve this issue implements - but this issue should not be
  scoped as "just add a command," since the underlying systematic scaling behaviour does not
  exist yet to be exposed by a command.

## Scope

This issue's primary deliverable is a concrete, implementable design for depth escalation,
followed by its implementation. At minimum the design must resolve:
1. Whether composition `weight`/`level`/`population` per region continues to be hand-authored
   per layer (cheapest, most controllable, least systematic - works today, just needs nether
   content once M14a lands and heavier middle/chaos/nether authoring), or whether a systematic
   multiplier (e.g. a per-layer "extra mob chance" or "bonus population" scalar feeding into
   `MineEncounterManager.spawnSite`'s composition/request resolution) is introduced so the curve
   is a tunable parameter rather than re-authored JSON for every balance pass - or both, with
   hand-authored compositions providing variety and a systematic scalar providing the "more of
   them, more often" trend.
2. Whether encounter-site *density* (`CELL`/`CELL_CHANCE` or their post-M14a per-layer
   equivalents) should also increase with depth, not just composition difficulty - the owner's
   wording ("more mobs... as deeper as you go") suggests yes, but this has real pacing
   consequences (deeper layers already take longer to reach and are already meant to feel like
   a commitment; too-dense spawns could make them unplayable rather than appropriately harder)
   and should be checked against the existing pacing design (`CaveStack.TARGET_BLOCKS_PER_PAIR`,
   the M13 session's walking-time tuning) before being decided.
3. How "different types" (variety) is measured and guaranteed - e.g. a minimum count of
   distinct entity ids across a region's compositions, increasing per layer, so "variety"
   is checkable rather than just "we added more compositions and hope it reads as varied."
4. Implement the resolved design: likely touching `encounters/standard.json` (authored content,
   including nether once M14a exists), and if a systematic scalar is chosen,
   `MineEncounterManager`'s composition/request resolution and/or `EncounterSites`'s per-layer
   density.

## Non-goals

- This issue does not re-litigate M12b's existing mob-difficulty stat scaling (`MobLevelScaling`)
  itself, only how compositions/density are *selected* by depth; M12b's health/damage/speed
  bands are an input to this issue's "harder mobs" goal, not something this issue redesigns.
- No change to the vanilla population-cap safety mechanism (`totalPopulationCap`,
  `Region.populationCap()`) beyond what's needed to raise per-region caps if the design requires
  more concurrent mobs in deeper regions - this issue must not remove or weaken that cap, which
  exists to protect server performance.
- Does not include the admin commands to tune this (M14b), only the underlying systematic
  behaviour M14b's `density`/`difficulty` commands would eventually expose, if a systematic
  scalar is the chosen mechanism.

## Dependencies and open decisions

- Depends on M14a: the nether layer needs to be a real region before it can participate in a
  four-layer escalation curve at all; writing nether compositions as the deepest, hardest tier
  is itself part of satisfying this issue's intent, not optional extra scope.
- Soft-depends on M14b: if a systematic per-layer scalar is the chosen mechanism (decision #1
  above), it should be designed so M14b's `density`/`difficulty` commands can read/write it once
  they exist, but this issue can ship as pure data-authoring first and be made commandable later
  without rework, if that ordering is more convenient.
- Open and explicitly unresolved by this planning conversation (owner has not been asked to
  choose yet): all four numbered decisions in Scope. The next step for whoever picks this issue
  up should be a short design pass (research existing composition/population numbers across all
  four layers, propose a concrete curve, get owner sign-off on the pacing trade-off in decision
  #2 specifically, since it has real risk of making deep layers frustrating rather than
  appropriately hard) before writing acceptance-testable code.
- Product owner: Durdeu Vlad-Ioan. Implementer: the next agent assigned this issue, who should
  expect to spend real design time here, not just implementation time. Reviewer: independent.

## Acceptance criteria

(Provisional - to be sharpened once the design decisions above are made; recorded now so this
issue is not vague busywork, but the implementer should expect to refine these against the
actual chosen mechanism before closing the issue.)

1. A documented escalation curve exists (in a new `docs/reports/` entry, same convention as
   M12a's rarity research) stating, per layer, the intended average population per encounter,
   the intended level bias, and the intended count of distinct entity types available - with
   reasoning, not arbitrary numbers.
2. Measured via `/dawnmine census` or a dedicated GameTest/unit test sampling encounter sites
   across a generated run: average mobs-per-site strictly increases from upper to nether; the
   count of distinct entity ids available per region strictly increases or at minimum does not
   decrease from upper to nether; the average/weighted composition `level` strictly increases
   from upper to nether.
3. The existing pacing design (walking time per layer, from the M13 session's
   `TARGET_BLOCKS_PER_PAIR` tuning) is not broken by any density increase - verified by rerunning
   the same `CaveStackTest` pacing assertions M13 already has.
4. Owner playtest confirms the felt difference between adjacent layers, specifically including
   chaos-to-nether (the pair most at risk of feeling identical, given the existing
   wither_skeleton/blaze overlap flagged in M14a).

## Verification

A design review step (this issue's own design-decision pass, point 1-4 in Scope) before any
code is written, since the mechanism itself is not yet chosen. Once implemented: `./gradlew test`
full suite including updated pacing assertions, `/dawnmine census` sampling across a fresh
reset, and owner playtest across all four layers in one session for a direct felt comparison.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals,
affected ownership, dependencies, commands/results, and residual risks - including which of the
four open design decisions were resolved and how, and explicit confirmation that pacing was not
degraded.
