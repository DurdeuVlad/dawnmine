# M9d Issue Draft — Procedural wall/structure motif pass

## Planning metadata
- Issue type: Feature / world generation
- Priority: Medium
- Milestone: M9 — Adventure revival (M9a leveling, M9b rare ore moments, M9c landmark safehouses, M9d structure motif pass)
- Assignee: Unassigned
- Target date: Not set

## Intent

The owner's core complaint was that the mine reads as "plain stone" with no texture, variety, or sense that someone/something shaped the space — they want AI-generated concept art translated into actual in-game structure, without a new texture/resource-pack pipeline and without depending on authored building files (AGENTS.md hard constraint). Research into vanilla Minecraft and comparable cave mods (recorded in this planning conversation) found that visual richness in those systems comes from a small number of composable, parametric generator rules applied repeatedly, not from one-off authored content. This issue builds that mechanism, generalizing a pattern the codebase already uses in miniature (`SegmentGeometry.addTimberFrame`/`addPassageLights`).

## Expectation

Passages and rooms gain a new, data-driven family of small structural/decorative motifs (e.g. a collapsed support cluster, a rubble/vein wall cluster, an abandoned-cart nook) placed probabilistically alongside the existing dress/ore passes. Each motif is defined as a parametric rule set, not a fixed grid, so every placed instance looks different in detail while still being recognizably the same designed "family" the source concept art depicts. No motif placement ever blocks a walkable route, and no motif ever hardcodes a specific mod's block id.

## Context that code cannot infer

- `SegmentGeometry.addTimberFrame` (mineshaft support posts/beam/lantern, placed every 7–9 centerline cells in `dressEdge`) and `addPassageLights` (sparse lamps on natural passages) are already exactly this kind of motif, just hand-written and singular. This issue generalizes that proven pattern into a reusable, data-driven family instead of writing more one-off methods.
- `dress()` already filters its own output against `wholeRunClearance`/`isJunctionObstacle` so decorations never block a shared walk cell; the new motif pass must reuse that exact filtering, not reinvent collision safety.
- `OreCatalog`'s tier lookup (`OreCatalog.active()`, `OreCatalog.Tier.parse(regionForY(pos.y()))`) is the established way to resolve "what ore/material is appropriate here" without hardcoding a specific vanilla or modded block id — any motif element that references an ore-like material must resolve through this, the same way `OrePlacementPlanner` already does, so the result automatically adapts to whatever ore mods are present per `ore_catalog/standard.json`.
- This is explicitly the largest, most architecturally novel issue in the M9 milestone — earlier planning in this conversation evaluated and rejected two alternative framings: (a) a literal fixed grid per motif (rejected — that is a "prefab," reproduces identically every time, and is a poor fit here; it is, however, the right fit for M9c's safehouses, which are deliberately static) and (b) treating AI concept art as texture-pack input (rejected — the owner explicitly does not want a new resource pack). The chosen framing is: AI concept art informs a human (optionally Codex-assisted) authoring pass that writes a *parametric rule set*, which code then regenerates differently every time it is placed.
- AGENTS.md's no-prefab-dependence constraint is satisfied specifically because placement and final geometry are decided by code at generation time from a rule set, never by loading a fixed authored structure file.

## Scope

- Add `data/dawnmine/motifs/*.json`: rule-set files with schema `{motifId, appliesTo: [edge_kind|node_kind values], region, spacingMin, spacingMax, elements: [{type, blockSlot, countRange: [min,max], offsetRule, chance}]}`. `blockSlot` values are abstract (e.g. `"support"`, `"rubble"`, `"ore_slot"`, `"light"`), never a concrete block id.
- Add `generation/MotifCatalog.java` (`SimpleJsonResourceReloadListener`, mirroring `MineEncounterCatalog`'s per-entry try/catch load-and-validate structure, including a bounded max count and required-field validation) to load and validate motif rule sets from that directory, failing closed (log and skip) on any invalid entry rather than crashing generation.
- Add `generation/MotifPlanner.java` (mirroring `OrePlacementPlanner`'s pure, seed-deterministic style: a static method taking the structural segment's existing `SegmentPlan`(s), the active `MotifCatalog` snapshot, the run seed, and the segment id, returning a new `SegmentGeometry.SegmentPlan` of `BlockOperation`s). Placement probability, spacing, and element counts are resolved from a `RandomSource.create(segmentSeed ^ stableHash(motifId))` exactly like `OrePlacementPlanner.planOreSegment` already seeds its own randomness, so results are reproducible per seed.
- Wire a new `motif:<structural id>` segment family into `PreparationPlan`/`MineGenerator.createPlan`, scheduled after the `dress:` pass completes for that structural segment (so motif placement can see and avoid the dressing's floors/supports the same way the ore pass already does), and filter the motif plan's output against `wholeRunClearance`/`isJunctionObstacle` exactly as `dress()` already filters its own output.
- Resolve any `"ore_slot"` element through `OreCatalog.active().choose(tier, random)` using the segment's region tier, never a literal block id.
- First minimum-viable content set: 3 motif types — a collapsed-shaft support cluster (natural extension of the existing mineshaft timber-frame idea), a rubble/vein wall cluster (loose rock + an embedded ore-slot block, echoing M9b's "jackpot" idea but at ordinary, frequent scale), and an abandoned-cart nook (a small dead-end alcove with a rail stub and scattered debris). This count is chosen to prove the architecture end-to-end without overcommitting content-authoring effort up front; more motifs are a straightforward follow-up once this pass works.

## Non-goals

No fixed/literal per-layer grids (that approach belongs to M9c's safehouses specifically, where "should not really change" is the explicit goal — this issue's motifs are the opposite: always procedurally varied). No new textures or resource pack. No runtime AI/image-generation calls — concept art is a design-time input to authoring the JSON rule sets, consumed entirely offline. No guarantee that every passage or room receives a motif; placement is probabilistic, the same caveat pattern M3 already applies to its own ore pool. No new entity/mob content.

## Dependencies and open decisions

- Depends on M2b (`SegmentGeometry`, `MineGenerator`'s segment dispatch, `PreparationPlan`, the existing `wholeRunClearance`/`isJunctionObstacle` filtering) and M3 (`OreCatalog` for `ore_slot` resolution).
- Recommended to land after M9c (landmark safehouses) since both extend the same generation-pipeline dispatch and clearance-filtering machinery; implementing M9c first gives this issue a proven second example of "new segment family + clearance filtering" to follow, reducing risk. Not a hard blocking dependency — the two can be built in parallel if staffed separately, but should be reviewed together for pipeline-dispatch consistency.
- Authoring each motif's rule set from AI-generated concept art, optionally with Codex's help, is an external, human-run step outside any automated coding session. It is `needs-input`/blocked-on-owner-action per motif and does not block implementing and testing the `MotifCatalog`/`MotifPlanner` architecture itself with placeholder/minimal rule sets.
- Open decision for the product owner (subjective, not resolved here): the final motif count, their exact visual direction, and which concept-art boards to commission first. `docs/concept-art/public-mines/` already holds the project's existing AI-concept-art convention (numbered boards + `builder-notes.md`) and is the natural home for any new boards commissioned for these motifs.
- Product owner: Durdeu Vlad-Ioan (this planning conversation). Implementer: the next agent assigned this issue. Reviewer: independent.

## Acceptance criteria

1. A motif rule set loaded from `data/dawnmine/motifs/*.json`, placed at the same structural segment under two different run seeds, produces two different concrete block layouts while both remain recognizably the same family (same element `type`s present, counts within the configured range).
2. No motif placement operation ever occupies a cell in `wholeRunClearance`, verified by the same kind of filter-assertion test already implied by `dress()`'s existing `isJunctionObstacle` removal step.
3. Every motif element referencing `ore_slot` resolves to a block actually present in the active `OreCatalog` snapshot for its segment's region tier; no motif ever contains a hardcoded concrete block id for an ore-like material.
4. An invalid or malformed motif definition (missing required field, out-of-range count, unknown `appliesTo` value) is logged and skipped at load time; generation continues using the remaining valid motifs, never crashing — mirroring `MineEncounterCatalog.apply`'s existing per-entry try/catch behavior.
5. Each of the 3 minimum-viable motif types generates without orphaned/floating blocks or passage-blocking geometry, sampled across at least 5 distinct seeds per motif type.
6. Re-running generation with the same run seed and the same motif catalog reproduces byte-identical motif placement (deterministic replay), consistent with the project's existing idempotent-segment-replay requirement (M2b acceptance criterion 1).

## Verification

Add unit tests on `MotifCatalog` parsing (valid and invalid fixtures, mirroring `MineEncounterCatalog`'s existing parser test style) and on `MotifPlanner` for deterministic seeded output and `ore_slot` resolution correctness. Add a dedicated-server GameTest file `MotifPlacementGameTests.java` sampling multiple generated segments for clearance violations (no motif cell inside `wholeRunClearance`) and for orphaned/floating-block geometry. Manually compare dev-server screenshots of each motif type, across several seeds, against its source concept art board for family resemblance — explicitly a subjective, manual check per this planning conversation's earlier test-surface analysis, not something to automate.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
