# M9c Issue Draft — Static landmark safehouses

## Planning metadata
- Issue type: Feature / world generation
- Priority: Medium
- Milestone: M9 — Adventure revival (M9a leveling, M9b rare ore moments, M9c landmark safehouses, M9d structure motif pass)
- Assignee: Unassigned
- Target date: Not set

## Intent

The owner asked for static landmark structures "like the lobby" (the expedition hub) placed deeper in the mine as safehouses, that should not really change and should have connectors on a side — i.e. a second category of fixed, hand-recognizable room, not another procedurally-varied cavern. The expedition hub (`HubLayout` + `SegmentGeometry.hub()`) already *is* exactly this pattern — a fixed-coordinate room built from explicit code, with a single reserved footprint and one declared opening. This issue generalizes that proven, existing mechanism into a reusable landmark type instead of inventing a new one.

## Expectation

A small number of nodes per run (flagged by a new topology feature) each generate a fixed, unchanging room from a literal, hand-authored per-layer block layout, anchored at that node's resolved position with one or more declared connector points on its boundary. Every other generation pass (structural carve, dress, ore, and the new motif pass from M9d) treats the room's interior as fully protected, the same way the hub's reserve is protected today, and every passage that must reach the room is routed to its declared connector point, never into its interior.

## Context that code cannot infer

- `HubLayout.java` already defines exactly this pattern for the hub: `RESERVE_HALF`/`isReserved`/`isProtected` define a protected footprint; `isGatewayBand` defines the one allowed opening; `SegmentGeometry.hub()` builds the room as explicit `MARKER` block operations at fixed coordinate offsets from a world-anchored origin. `MineGenerator.createPlan`/`finishSegment` special-case the literal string `"hub"` as a segment id, and `MineGenerator`'s write loop explicitly exempts `"hub"` from the arrival-keepout failure check (the `if (!segmentId.equals("hub") && SegmentGeometry.isArrivalKeepOut(...))` check). This issue generalizes that single hardcoded case into a small registry of landmark types.
- Existing documented project convention (`docs/concept-art/public-mines/builder-notes.md`, "Builder constraints"): checkpoint-style stations must use "compact, repeatable station footprints that can fit algorithmically generated caverns" and must **not** "require a prefab mansion or fixed world structure." That convention governs the *existing* adaptive checkpoint pad (`SegmentGeometry.addCheckpointPad`, a 5×5 pad that fits whatever cavern shape surrounds it) and is **not** changed by this issue. Safehouses are a deliberate, separate, additional category of node — not a replacement for ordinary checkpoints, and not applied to every checkpoint. This distinction must be preserved so a future reader does not think this issue reverses that convention.
- `docs/art/README.md` and `docs/concept-art/public-mines/builder-notes.md` reference a stale data path (`data/publicmines/...`); the live mod id/data namespace is `dawnmine` (confirmed against `src/main/resources/data/dawnmine/`). This is a pre-existing documentation drift from an earlier mod-id rename, unrelated to this issue; flagging it here only so an implementer does not follow the stale path by mistake. Fixing that drift is optional opportunistic cleanup, not required scope.
- AGENTS.md's no-prefab-dependence rule is satisfied here the same way it is already satisfied by the hub: the room is defined by code/data consumed by code (a JSON per-layer block grid loaded and interpreted by a new Java builder), never by an NBT structure file loaded through vanilla's structure-template system. No NBT structure placement exists anywhere in this codebase today (confirmed by search) and this issue does not introduce one.
- `TopologyProfile.RegionSpec` already carries per-region tunable counts (`caverns`, `branches`, `checkpoints`, `landmarks`, etc.) validated in `TopologyProfile.validate()`; a new `safehouses` count follows the same established pattern.

## Scope

- Add `NodeFeature.SAFEHOUSE` to `topology/NodeFeature.java`, following the existing enum's documentation style (one line stating which milestone/feature owns it).
- Add a `safehouses` integer field to `TopologyProfile.RegionSpec`, parsed and validated in `TopologyProfile.fromJson`/`validate()` the same way `landmarks`/`checkpoints` are validated today (non-negative, bounded by the region's node budget). Update `topology_profiles/standard.json` with a starting value of `1` per region.
- Wherever regions assign `NodeFeature.LANDMARK`/`NodeFeature.CHECKPOINT` to nodes today (the topology-planning code that reads `RegionSpec` counts), add the equivalent assignment of `NodeFeature.SAFEHOUSE` to exactly `safehouses` nodes per region. Recommended (open decision below): a safehouse node is always also a `CHECKPOINT` node, so a safehouse always doubles as a bank point.
- Add `topology/LandmarkLayout.java`: given a landmark type's declared footprint half-extents and one or more relative connector offsets, plus a concrete anchor `TopoPos` (the node's resolved center) and a simple orientation, compute (a) an `isReserved(TopoPos)`-style predicate for that specific placed instance and (b) the absolute connector position(s) — i.e. the same two capabilities `HubLayout` provides for the hub, but parameterized instead of hardcoded to world origin.
- Add `data/dawnmine/landmarks/*.json`: one file per safehouse type, each a literal, final per-layer block grid (schema: `{id, footprint: {halfWidth, halfHeight}, connectors: [{x,y,z,facing}], layers: [[[blockSlot,...]...]...]}`). Block slots resolve through the same abstract-slot convention used elsewhere (e.g. `"wall"`, `"floor"`, `"light"`, `"ore_slot"` resolving via `OreCatalog` tier lookup) so the room stays vanilla+mod-ore-agnostic. This file is the literal translation target for AI-generated concept art — see Dependencies.
- Add `generation/LandmarkCatalog.java` (`SimpleJsonResourceReloadListener`, mirroring `MineEncounterCatalog`'s load/validate/fail-closed structure) to load and validate landmark definitions from that directory.
- Add `generation/StaticLandmarkBuilder.java`: consumes a loaded `LandmarkCatalog` entry plus a `LandmarkLayout`-resolved anchor and emits a `SegmentGeometry.SegmentPlan` of `MARKER`/`OPEN` block operations, structured exactly like `SegmentGeometry.hub()` (deterministic, sorted output, no RNG — the room is literal and final, never procedurally varied).
- Add a new segment-id family `"landmark:" + nodeId` to `PreparationPlan` (alongside the existing `"hub"`, `node:`, `edge:`, `dress:`, `ores:`, `connector:arrival:` families) and dispatch it in `MineGenerator.createPlan`, scheduled after the node's structural carve.
- Generalize the single `!segmentId.equals("hub")` exemption in `MineGenerator`'s write loop into a check against *any* registered landmark instance's reserved footprint (hub included), and extend the keepout predicate already passed into `OrePlacementPlanner.planOreSegment` (currently `SegmentGeometry.isArrivalKeepOut(pos, ARRIVAL) || HubLayout.isReserved(pos) || pads.contains(pos)`) to also exclude every placed safehouse's footprint.
- Ensure every edge whose endpoint is a `SAFEHOUSE` node terminates at that landmark's declared connector position rather than the node's raw `pos()` — mirroring how `connectorCenterline`/`avoidGatewayApron`/`avoidForeignReserve` already force routing through the hub's single `EAST_EXIT`/gateway band instead of letting a path wander into the hub's interior.

## Non-goals

Does not change, resize, or replace the existing adaptive checkpoint pad (`addCheckpointPad`) and does not apply to checkpoints that are not also flagged `SAFEHOUSE`. Does not change the hub itself beyond generalizing its exemption check. No new textures or resource pack. No procedural variation of a placed safehouse's interior — once placed, it is literal and final (procedural variety is M9d's job, for a different kind of content). No multi-room or branching safehouse interiors in this first version.

## Dependencies and open decisions

- Depends on M2a (`TopoNode`/`NodeFeature`/`TopologyProfile`) and M2b (`SegmentGeometry`, `MineGenerator`'s segment dispatch, `PreparationPlan`). Reuses and must not regress the hub's existing reserve/routing behavior and its existing tests.
- This issue's connector-routing requirement overlaps with `PassageNetwork`'s existing node-center resolution (`PassageNetwork.of(topology, arrival).center(node.id())`); the implementer must confirm whether `PassageNetwork` needs a new override for safehouse nodes or whether routing edges toward the landmark's connector can be layered on top of the existing center resolution without changing `PassageNetwork`'s own contract. Flagging as an implementation detail to resolve during `flux-plan`/`flux-build`, not a product decision.
- Flags the existing `docs/concept-art/public-mines/builder-notes.md` convention against fixed/prefab-shaped stations (see Context). This issue deliberately adds a second, separate category of fixed structure, approved by the product owner in the planning conversation that produced this milestone; it does not reverse that convention for ordinary checkpoints.
- Open decision for the product owner: exact `safehouses` count per region (proposed: 1 per region to start) and whether a safehouse node must always coincide with a `CHECKPOINT` node (recommended: yes, to reinforce the existing bank-or-push pillar) or may stand alone.
- AI-concept-art-to-grid authoring (the literal per-layer block grid in `data/dawnmine/landmarks/*.json`) is intended to be produced offline with the help of Codex, run by a human outside any automated coding session — this is an external action this issue cannot execute on its own. The architecture and tests in this issue must work and be verifiable with at least one placeholder/minimal landmark definition even before a Codex-authored grid exists.
- Product owner: Durdeu Vlad-Ioan (this planning conversation). Implementer: the next agent assigned this issue. Reviewer: independent.

## Acceptance criteria

1. A `LandmarkCatalog`-loaded safehouse definition, placed at a given anchor, produces a byte-identical `SegmentPlan` across repeated generations with the same definition and anchor (same determinism guarantee as `SegmentGeometry.hub()`).
2. Every cell inside a placed safehouse's reserved footprint is protected from foreign structural-carve, dress, and ore writes — proven the same way the hub's reserve protection is already proven (no foreign `BlockOperation` lands inside `isReserved`).
3. Every edge routed toward a `SAFEHOUSE` node terminates exactly at one of that landmark's declared connector cells, never at an arbitrary point on its footprint boundary and never inside its interior.
4. Exactly `safehouses` nodes per region are flagged `SAFEHOUSE`, deterministically from the run seed (same seed → same safehouse nodes).
5. A missing or invalid landmark definition referenced by a `SAFEHOUSE` node fails closed with a logged diagnostic — generation does not silently skip the feature or substitute a different room, matching the fail-closed pattern already established in `MineEncounterCatalog`/`MineEncounterManager` ("do not silently substitute").
6. The existing hub's generation, reserve, and gateway behavior are unaffected: all currently-passing hub-related tests still pass after the `"hub"`-specific exemption is generalized into the new landmark-registry check.
7. Restarting the server mid-generation of a `"landmark:"` segment resumes or safely replays without holes or unsafe pads, matching M2b's existing acceptance criterion 4 for other segment families.

## Verification

Add unit tests on `LandmarkLayout`/`StaticLandmarkBuilder` proving deterministic output and correct connector-position resolution for a placeholder landmark definition. Add a dedicated-server GameTest file `LandmarkGameTests.java` verifying footprint protection (no foreign operation lands inside the reserve), connector attachment (the routed edge's final cell matches the declared connector), and restart-mid-generation replay safety. Extend `MineLifecycleGameTests.java` coverage if it already exercises restart/replay for other segment families, to confirm no regression. Manually verify on a dev server that a generated safehouse is reachable, enterable, visually static, and unchanged across a reseed-and-regenerate cycle using the same seed.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
