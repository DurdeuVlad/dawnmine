# M9b Issue Draft — Rare high-value ore veins and geode moments

## Planning metadata
- Issue type: Feature / resource progression
- Priority: Medium
- Milestone: M9 — Adventure revival (M9a leveling, M9b rare ore moments, M9c landmark safehouses, M9d structure motif pass)
- Assignee: Unassigned
- Target date: Not set

## Intent

Research into how vanilla Minecraft and comparable cave mods make exploration feel worthwhile (recorded in this planning conversation) found a recurring pattern: most ore comes from small, uniform clusters, but rare, much larger veins or geodes create deliberate "jackpot" moments distinct from routine mining. The mine currently places ore at a flat per-segment rate (`OrePlacementPlanner.ORDINARY_VEINS` / `RESOURCE_FIELD_VEINS`) with no equivalent rare-moment mechanic. This issue adds one, reusing the existing catalog and planner rather than inventing new content.

## Expectation

A small, fixed number of cavern nodes per region, chosen deterministically from the run seed, roll a visibly larger and higher-value ore placement than any ordinary node of the same kind — using only ore blocks already registered in `ore_catalog/standard.json`. The effect must be rare enough to read as a surprise, not a new baseline rate.

## Context that code cannot infer

- M3's existing non-goal explicitly rules out new ore blocks or loot-table changes; this issue must reuse existing `OreCatalog` entries only.
- Current baselines: `OrePlacementPlanner.ORDINARY_VEINS = 3`, `RESOURCE_FIELD_VEINS = 8`, `VEIN_PATH_SPACING = 12`. A "jackpot" must be clearly above `RESOURCE_FIELD_VEINS`, not a marginal increase, to actually read as a distinct moment.
- `OreCatalog.Entry.maxVeinSize()` already bounds how large a single cluster can grow (`OrePlacementPlanner.clusterFrom`); the simplest, lowest-risk way to create a bigger moment is to raise the *vein target* (number of cluster attempts) and bias tier selection for that node, not to change `maxVeinSize` globally.
- Everything in `OrePlacementPlanner` is a pure, deterministic function of the run seed and segment id — this issue must preserve that property; no new mutable/random state outside the existing `RandomSource.create(segmentSeed)` pattern.
- Must not weaken M3's accepted Chaos-tier lava/quartz containment or the Netherrack bounded-rarity config.

## Scope

- Add a deterministic "jackpot node" selection: a pure function, e.g. `OrePlacementPlanner.isJackpotNode(TopoNode node, long seed)`, returning true for exactly a configured count of `CAVERN` nodes per region (proposed starting point: 1 per region, i.e. 3 jackpot nodes per run), chosen by hashing `seed` against each eligible node's id the same way `stableHash`/`mix` already select mineshaft vs. natural passage style elsewhere in `SegmentGeometry`.
- Add a jackpot vein-target helper, e.g. `OrePlacementPlanner.jackpotVeinTarget(int base)`, returning a multiple of the base target (proposed starting point: ×3–4 of `RESOURCE_FIELD_VEINS`).
- Wire this into `MineGenerator.createOrePlan`/`veinTargetFor` (where the ordinary `ORDINARY_VEINS`/`RESOURCE_FIELD_VEINS` choice is currently made) so a jackpot node's ore segment plans with the elevated target instead of the ordinary one.
- Bias tier/entry selection for a jackpot node toward the rarer end of its tier's weighted pool. The lowest-risk implementation is to let the existing `OreCatalog.choose(tier, random)` weighting do this naturally once more draws happen (more draws against the same weighted pool statistically surfaces more low-weight/high-value entries) — do not add a second, parallel selection mechanism unless a straightforward higher-draw-count approach proves insufficient in testing.
- No `ore_catalog/standard.json` schema change is required for this version. If the product owner later wants a visually/mechanically distinct "named vein" block type, that is an explicit follow-up decision (schema change + possibly new structural dressing), not assumed here.

## Non-goals

No new ore blocks or catalog entries. No change to the Netherrack default-off bounded-rarity config. No guarantee that every run visits a jackpot node — placement is probabilistic/positional per the existing weighted-selection and topology-generation pattern, the same caveat M3's own acceptance criterion 4 already states for ordinary resource fields. No economy/balance claim.

## Dependencies and open decisions

- Depends on M3 (`OreCatalog`, `ore_catalog/standard.json`) and M2b (`OrePlacementPlanner`, `SegmentGeometry`, `MineGenerator`'s ore-plan dispatch). Must not regress M3's accepted acceptance criteria 4–6 (tier pool membership, Chaos quartz/lava containment, Netherrack bounds).
- Independent of M9a, M9c, and M9d.
- Open decision for the product owner: exact jackpot-node count per region and the exact vein-target multiplier — the values above are a starting point for playtesting, not a locked balance claim.
- Product owner: Durdeu Vlad-Ioan (this planning conversation). Implementer: the next agent assigned this issue. Reviewer: independent.

## Acceptance criteria

1. Exactly the configured number of `CAVERN` nodes per region are flagged as jackpot nodes for a given run, and the same run seed always flags the same nodes (deterministic replay).
2. A jackpot node's planned ore-block count from `OrePlacementPlanner.planOreSegment` is strictly greater than `RESOURCE_FIELD_VEINS` for an ordinary node of the same kind, verified by direct comparison in a unit test using fixed seeds/fixtures.
3. Every block placed at a jackpot node resolves to an entry already present in the active `OreCatalog` snapshot for that node's tier — no new or out-of-catalog block id ever appears.
4. M3's existing acceptance criteria 4–6 (resource-field tier pool membership, Chaos containment, Netherrack bounds) remain unaffected by this change.
5. Re-running generation with the same topology/run seed reproduces identical jackpot node selection and identical placed ore operations (byte-for-byte `SegmentPlan` equality), consistent with the project's existing idempotent-replay requirement (M2b acceptance criterion 1).

## Verification

Add unit tests on `OrePlacementPlanner` comparing jackpot vs. ordinary vein-target output for fixed seed/fixture pairs, and asserting deterministic jackpot-node selection across repeated runs with the same seed. Add a dedicated-server GameTest sampling a generated run's cavern nodes and asserting at least the configured jackpot count exists with elevated ore density, reusing the wall-audit style already present in `OreBreakGameTests.generatedOresArePresentAndExposedOnWalls`. Manually playtest a few seeds to confirm the jackpot moment is noticeably larger/rarer and does not read as the new normal rate.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
