# M12e Issue Draft — Dropper-shaft connectors and a themed nether layer

## Planning metadata
- Issue type: Feature / world generation
- Priority: Medium
- Milestone: M12 — Owner playtest fixes: ore density, mob difficulty/drops, loot, layer connectivity, mob variety
- Assignee: Unassigned
- Target date: Not set

## Intent

Owner playtest raised two related doubts: whether the cave layers are actually connected, and where the expected "layer 4"/nether content is. The owner then proposed a concrete fix: special vertical "dropper" rooms (a shaft with ladders and a water landing) placed at increasing straight-line distance rings from the spawn/hub center — 1000 blocks for the layer 1→2 transition, 2000 for layer 2→3, 3000 for a new layer 3→4 (nether-themed) transition — as a more deliberate, readable way to connect layers than relying solely on finding the existing descent tunnels.

## Expectation

Each layer transition has at least one dropper-style connector: a vertical shaft lined with ladders, ending in a water landing to prevent fall damage, placed at a roughly fixed distance from the mine's center so players can navigate toward it deliberately. A fourth, nether-themed cave layer exists (same mine dimension, not the real Nether — confirmed by the owner), reached via the layer 3→4 dropper ring.

## Context that code cannot infer

- Current connectivity is already measured and is **not** actually broken: the live census run on the current build reports `walkable: standable=3725342 reachable=3725319 unreachable=23` — 99.9994% of standable space is reachable, with only 23 stray unreachable cells. The owner's "are the layers even connected?" question was a reasonable doubt from playing, but the real gap is readability/deliberateness of the connection, not actual disconnection. The 23 residual unreachable cells are a tiny, separate bug worth a quick look but are not the reason this issue exists.
- Current connector mechanism (`CaveStack.java`): exactly 3 named descent tunnels per layer pair ("steep", "gentle", "bypass"), placed at a *walking-distance* target (`TARGET_BLOCKS = 3600`) from the layer's entry point, not at a fixed straight-line distance from the world's spawn/center. The live census shows actual walking distances to each descent around 3,000-3,300 blocks for layer 0→1 and roughly 6,600-6,800 blocks cumulative for layer 1→2 — i.e. the existing system already paces descents by distance, just by a different distance metric (walking distance along generated terrain, measured from each layer's own entry) than the owner's proposal (straight-line distance from the fixed world spawn, cumulative across layers: 1000/2000/3000).
- `CavePlanner.LAYERS = 3` today — there is no 4th layer. Owner confirmed via clarifying question: the nether layer should be a themed 4th `CaveStack` layer inside the mine's own dimension (netherrack/basalt/lava palette), not an actual teleport to `minecraft:the_nether`. This keeps it inside the existing run/reset/safe-zone/protection systems rather than opening a second dimension's worth of new rules.
- This issue therefore has two distinct parts that must both be resolved, not assumed: (a) whether "droppers" *replace* the existing three-descents-per-pair mechanism, or are an *additional*, more direct connector alongside the existing three (open decision below), and (b) building the actual 4th layer's terrain/ore/mob/landmark content, which is new scope on the same scale as one of the original three levels, not a small addition.

## Scope

- Add a fourth `CaveStack`/`CavePlanner` layer (`LAYERS = 4`) with a nether-appropriate block palette (netherrack, basalt, lava), following the same per-layer spec/sheet/descent pattern already used for layers 0-2. This reuses `NetherrackPlacementPolicy`'s existing bounded-rarity netherrack logic as a palette reference, but this layer's netherrack use is a full layer theme, not the existing sparse-cluster feature — reconcile the two so the existing bounded-rarity Netherrack config (AGENTS.md: default off, one attempt per 256 eligible columns at deepest tier) is not silently violated by a layer that is now netherrack-heavy by design; flag this to the owner if the existing Netherrack cap needs an explicit exception for this new layer.
- Implement the "dropper" room type: a vertical shaft, ladder-lined walls, water at the bottom (fall-damage-safe landing), connecting two adjacent layers. Placement follows the owner's proposed rings (distance from mine center, cumulative: ~1000 blocks for layer 1→2, ~2000 for layer 2→3, ~3000 for layer 3→4) — exact numbers are a tuning decision for the owner to confirm once a first version is in-game, since they don't yet account for how layer 0's existing ~3,600-block walking-distance pacing interacts with a much shorter 1,000-block straight-line ring.
- Extend ore (`OreField`/`CaveTerrain`), encounters (`EncounterSites`, `MineEncounterCatalog`), and landmarks (`Landmarks`/`LandmarkCatalog`) to cover the new 4th layer with region tier `nether` (or similar), following the exact same per-region extension pattern already used for upper/middle/chaos.
- Investigate and fix the 23 residual unreachable cells found by the current census run as a small, separate cleanup within this issue (same connectivity-layer system, low effort, good opportunity to close it out here) — report the fix's before/after unreachable count.

## Non-goals

Does not teleport players to the real `minecraft:the_nether` dimension (explicitly rejected by the owner). Does not remove or weaken the existing bedrock-shell/owned-volume/protection systems for the new layer. Does not change the existing bounded-rarity sparse-Netherrack feature's own config/behavior outside this new layer. Does not attempt to retune the overall per-layer pacing targets from `Mine-Concept-v2.md` section 10.1 (15/30-45 min) beyond what adding a 4th layer and new connectors naturally requires — if the owner's proposed distance rings meaningfully change overall run length, flag that back to the owner rather than silently absorbing it.

## Dependencies and open decisions

- Depends on M11 (`CaveStack`, `CavePlanner`, `CaveTerrain`, `OreField`, `Landmarks`, `EncounterSites`) for the layer/content pattern to extend, and on M12a (ore rarity sizing) landing first or in parallel so the new layer's ore doesn't inherit the sizing bug.
- **Open decision (owner confirmed partially, not fully):** does the dropper mechanism *replace* the existing three-descents-per-pair system, or sit alongside it as a fourth, more direct way down? Recommendation: keep the existing three (they already serve the "multiple distinct routes down" pacing pillar from the approved concept doc) and add droppers as an additional, deliberately central/direct connector — but this needs the owner's explicit confirmation before implementation, since it changes how big a change this really is.
- **Open decision:** the owner's proposed distance rings (1000/2000/3000, straight-line from center) use a different distance metric than the existing system's walking-distance-from-layer-entry (currently ~3,600 blocks/layer). Implementer should reconcile these and confirm the final numbers with the owner once a first version is testable, rather than guessing a resolution that changes overall pacing silently.
- Product owner: Durdeu Vlad-Ioan. Implementer: the next agent assigned this issue. Reviewer: independent.

## Acceptance criteria

1. `CavePlanner.LAYERS` is 4, and the new layer generates with a distinct nether-themed block palette, consistent with the existing per-layer identity pattern.
2. At least one dropper-style connector (ladder shaft + water landing) exists per layer transition, placed at or near the owner-confirmed distance rings.
3. `/dawnmine census` on the new build reports full (or near-full, with any remainder explicitly explained) reachability across all four layers, and the new layer's ore/mob/loot density meets the same per-region targets already set for upper/middle/chaos.
4. The residual 23 unreachable cells found in this playtest's baseline census are resolved or explicitly explained (down from 23, with evidence).
5. The existing bounded-rarity sparse-Netherrack feature's own config and behavior (default off, 256-column interval, three-block cluster cap) are unaffected outside the new layer.
6. No change to the real `minecraft:the_nether` dimension or to any system outside `MineManager.MINE_DIMENSION`.

## Verification

Run `/dawnmine census` before and after on the same seed to show the connectivity/reachability numbers and the new layer's density, using this issue's documented baseline (`unreachable=23`, `LAYERS=3`) for comparison. Add unit tests on the new layer's `CavePlanner.Spec`/`CaveStack` construction and on dropper placement distance. Manual playtest by the owner walking a dropper shaft and confirming the fall-safe water landing and layer identity read correctly.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
