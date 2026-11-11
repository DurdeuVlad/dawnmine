# M11 - Mine v2 rebuild (milestone plan and issue drafts)

Plan status: **M11-0 is `ready-for-handoff`. M11-1 to M11-9 are `draft`**: their numeric targets are proposals that M11-0's baseline and M11-1's measured walking speed must confirm before each issue starts (stated per issue). Issues are local drafts; no GitHub issues were created (not requested).

Source of truth for intent: [docs/Mine-Concept-v2.md](../Mine-Concept-v2.md) (owner-approved 2026-11-11, owner decisions in its section 10).

## Milestone

**Outcome:** The mine is a vanilla-style cave system, three layers deep, with multiple routes down each layer, vanilla-gameplay landmarks in the player's way, ore you always see, mobs you always meet, and mine-native loot you find. A layer takes about 15 min to rush and 30-45 min to explore. A non-technical builder can later replace any landmark through guided commands.

**Scope:** Replace `topology` and `generation` (graph, shapes, sealer); add census tooling; add cave terrain, route, landmark (POI), ore, encounter and loot systems; add three layers; add builder tooling; remove superseded code.

**Keep untouched (infrastructure):** run lifecycle and daily reset, dimension and bedrock shell, protection rules, Dispatch lockers, teleporter, hub (authored plus fallback), ore catalogue and native-break audit, Born in Chaos integration, config, admin commands, whole-run pre-generation and chunk tickets, CI and release.

**Non-goals (milestone):** pack-specific treasure or gear drops; new custom blocks, textures or a resource pack; changes to the factions mine or other dimensions; a custom death penalty; production deployment or stable release; claiming the mine "is fun" from tests (owner playtest verdict only).

**Depends on:** M0-M10 merged on `main` (HEAD `ba86813`).

**Proof of completion:** census targets met over a fixed seed set; zero unreachable standable cells; owner playtest accepts each layer; existing protection, reset, Dispatch, teleporter and ore-break regressions pass; a fresh reset on the local test server completes cleanly.

**Risks:** (1) noise caves create disconnected pockets (mitigation: flood-fill connectivity pass in M11-1); (2) mob and loot density hurts server performance (mitigation: caps and measured tick budget); (3) whole-run pre-generation time grows with layer size (mitigation: measure per layer; budget stated in M11-1); (4) rebuild leaves the test mine broken between stages (accepted: owner allows breaking changes; each stage must still boot and reset cleanly on the local test server); (5) "fun" is empirical (mitigation: owner playtest gate per stage).

**Assignee:** unassigned. **Type:** Feature. **Priority:** High. **Target date:** not set.

## Order

M11-0 -> M11-1 -> M11-2, M11-2b (ore) and M11-3 (parallel) -> M11-4 and M11-5 -> M11-6 -> M11-7 -> M11-8 -> M11-9. Owner playtests Level 1 after M11-5 and each later layer before the next begins. M11-8 may start any time after M11-3.

## Mandatory PR contract (every M11 issue)

Each PR body repeats: intent, expectation, acceptance criteria, non-code context, scope and non-goals, affected areas, dependencies, verification evidence (commands, census output, live-server log), assumptions and open decisions. "Not applicable", "None" or "Unknown-blocked" is stated explicitly where it applies.

## Shared context that code cannot infer (applies to all issues)

- Owner decisions 2026-11-11: layer pace is about 15 min rush / 30-45 min explore with 3+ routes down per layer; vanilla death rules stay; Level 1 is solo-safe, Level 3 is team-tuned; feel is the vanilla cave system with occasional vanilla-gameplay landmarks; everything ships procedural, authored overrides come later; loot is mine-native only (ores, raw ores, coal, gems, stone and mineral blocks, rails, torches, TNT, mine-tier tools); infrastructure stays, `topology`/`generation` are replaced.
- AGENTS.md: the mine is code-generated; only the spawn hub may be authored. Authored landmark overrides (M11-8) extend the owner-approved hub exception and need the owner's explicit sign-off at that issue.
- Owner playtest of v1 found: no mobs, too little ore, no loot, not fun to explore. v1 numbers are the baseline M11-0 measures.
- **Owner 2026-11-11: breaking changes are fine; do not anchor to v1.** Superseded code, data formats, config keys and tests may be deleted or changed incompatibly as soon as the replacement exists. Remove v1 code as each stage replaces it rather than waiting for M11-9.
- Production deployment remains unauthorized. Test-server proof uses the local `rusticcraft2-clona` clone.

---

## M11-0 - Census tool and v1 baseline  [ready-for-handoff]

**Intent.** Stop judging the mine by eye. Give owner and agents one command that states how much ore, how many encounters, how much loot and how many landmarks a run has, and whether any standable cell is unreachable. Needed now because the rebuild's acceptance criteria are census numbers.

**Expectation.** `/dawnmine census` (permission level 2) scans the active prepared run and reports, per layer/region: exposed-ore share of wall surface by ore type; encounter count and mobs per 100 route-blocks; loot container count per 100 route-blocks; landmark count and spacing; longest stretch of route with no ore, encounter, loot or landmark; unreachable standable cell count; route length and a rush-time estimate at a configurable walking speed. A pure core runs in unit tests from a plan or block snapshot without a server.

**Acceptance criteria.**
1. Command reports every metric above for each region, in-game and to the log, and is read-only (no block, entity or SavedData change).
2. The same seed gives byte-identical output twice.
3. A baseline report for the current v1 mine is committed to `docs/reports/M11-0-v1-baseline.md` and states the v1 values for ore share, encounters, loot and longest empty stretch. (Amended at delivery: one live run, not 5 seeds, because v1 is being replaced; M11-1 onward use a fixed multi-seed set.)
4. Unreachable-cell count equals an independent flood-fill result on a hand-built fixture with known traps.
5. Non-operators cannot run it. Running it during preparation reports "not ready" instead of partial data.

**Scope.** New `census` package plus command wiring; no generation behaviour change. Affects `command/DawnmineCommand`, new `census/*`, tests.

**Non-goals.** No tuning, no targets enforcement in CI yet (M11-1 onward adds them), no change to generation.

**Dependencies and open decisions.** Depends on merged `main`. Open (non-blocking): default walking speed for the rush estimate (proposal 4.3 blocks/s sprint-walk on flat, to be refined from a measured walk).

**Verification.** Unit tests on pure scanners and fixtures; `./gradlew clean test`; run the command on the local test server after a fresh reset and attach output; independent review.

---

## M11-1 - Cave terrain, connectivity and layer shell  [in delivery]

**Intent.** Replace the room-and-tunnel graph with a vanilla-feel cave system as the base of one layer, because the owner's reference is the vanilla cave system and the graph shape caused the trapped-pocket bugs.

**Expectation.** For Layer 1, whole-run preparation carves deterministic noise caverns, worm tunnels and ravines inside layer height bands, attaches them to the existing hub, then runs a connectivity pass (flood fill from the hub) that connects or seals every unreachable standable area. Layer bounds, floor/ceiling and vertical drop to the next layer are defined as data. Preparation time and tick cost are measured and bounded.

**Acceptance criteria.**
1. Same seed gives identical terrain; different seeds differ (hash of block snapshot).
2. Census unreachable standable cells = 0 on 20 fixed seeds.
3. Cave volume share, tunnel width range and cavern height range fall inside documented bounds (proposed from vanilla; confirmed after M11-0 baseline).
4. Hub arrival and keep-outs are preserved; protection and bedrock shell tests still pass.
5. Whole-layer preparation time on the local server is recorded and stays under a documented budget (proposal: under the current v1 full-run time).
6. Breaking changes are allowed (owner, 2026-11-11): v2 replaces v1 outright; no config switch and no migration of old runs or saved topology data are required. Existing runs are discarded at the next reset.

**Scope.** New `cave` package (pure planner) plus `generation/CaveTerrain`, applied in the run-seed chunk generator and the reset baseline; preparation is reduced to the hub; superseded v1 segments (node/edge/dress/motif/ore, mineshaft placement, pocket sealing, encounter spawning) no longer run. No ores, mobs, loot or landmarks until M11-2b/4/5.

**Design decision (2026-11-11).** Walkability is built in, not repaired: each layer is a 2.5D sheet (floor y and open height per column) whose floors are chamfered so neighbouring floors differ by at most 1, so there are no cliffs or pits (players cannot dig out: only ores are breakable). Caverns and spaghetti tunnels come from 2D noise; open regions are labelled and joined to the hub forecourt by channels, so there is one region. Multiple stacked sheets and descents are M11-2. Old v1 code is deleted in a follow-up cleanup PR once nothing references it.

**Non-goals.** Multiple routes down (M11-2), landmarks (M11-3), Layers 2-3, removing v1 code.

**Dependencies and open decisions.** After M11-0. Open: layer height bands and count of layers' vertical room within the dimension height (decide from the dimension's actual min/max Y at plan time, not assumed).

**Verification.** Unit tests (determinism, connectivity over seeds), dedicated-server preparation run with log and timing, census output, independent review.

---

## M11-2 - Multiple routes down, loops and walking-pace sizing  [in delivery]

**Design decision (2026-11-11).** Three stacked cave layers, each spanning the whole shell, joined by three descent tunnels per layer pair (steep, gentle, bypass: slopes 0.5, 0.25, 0.4, 118-216 steps long). A long rock ridge across each layer with one wide gap at the far end forces the way down to cross the map, and the descent sites are chosen by measured walking distance (Dijkstra, 10/14 weights) to about 3,600 blocks from where the layer is entered. Tunnels are 3 wide, 5 high, level for 12 steps at each end, and land in a pinned level patch of the lower layer; both layers are closed around the tunnel's middle so it never undercuts a floor or opens on a cliff. Shell radius is restricted to 64-96 chunks. Census gains waypoint walking times in real blocks (diagonal steps count 1.41).

**Acceptance criteria (as delivered).**
1. Each layer pair has 3 distinct descents (distinct tunnels, 100-450 blocks apart, different slope and length), each reachable on foot from the hub by the census walker. Edge-disjoint route counting by max-flow was not built; independence is by distinct tunnels and landings.
2. Rush to the way down is about 15 minutes at 4.3 blocks/s: tests assert 11-18 min for layer 0 and 9-19 min for the layer-1 leg on 3 seeds; live: layer 0 at 13.2-13.6 min, layer 1 leg about 13.5 min. Explore time (30-45 min per layer) is **not measured** (no metric yet); the walkable area is 3.5M standable cells.
3. Route character rules (hazards on the fast route, ore on the long route) are **unsupported** until hazards and ore exist (M11-2b, M11-4).
4. Every route is walkable without placing blocks: the census walker (no drops over 1 block) reaches every standable cell.
5. Zero unreachable standable cells: full three-layer mine on 3 seeds plus 20 single-layer seeds plus a 150-seed gateway check; live full mine: 21 unreachable, all in or beside the old hub build.

**Dependencies.** After M11-1.

## M11-3 - Landmark (POI) framework and vanilla-style landmark set  [draft]

**Intent.** The owner wants occasional landmarks from vanilla gameplay in the player's path. A framework lets us add them one by one, and later lets a builder replace any of them.

**Expectation.** A landmark registry places typed landmarks along and off routes (Poisson-disk spacing, per-layer tables) via code-built procedural implementations. Initial set: mineshaft with rails, ravine bridge, spawner dungeon room, geode, fossil, flooded cave, buried ruin, collapsed shaft, plus the Foreman's Office safe room. Each landmark declares footprint, connectors, and hooks for ore, encounter and loot (filled by M11-2/4/5 systems). Each type has an optional authored-override slot (not used yet).

**Acceptance criteria.**
1. >= 8 landmark types generate on a fixed seed set; spacing and density per layer meet census targets (set after baseline; initial proposal: a landmark every 60-100 route blocks).
2. Landmarks never block routes, overlap the hub or each other; connectivity stays 0 unreachable.
3. Determinism per seed; each landmark has a unit test on its plan.
4. Landmark footprint contract documented (size, connectors, hooks) so M11-8 can reuse it.

**Scope.** New landmark package replacing `LandmarkCatalog`/`MotifPlanner` usage; Foreman's Office as safe zone using existing checkpoint rules. Non-goals: authored overrides, builder commands (M11-8), encounter/loot content.

**Dependencies and open decisions.** After M11-1; consumes M11-2 routes. Open: final landmark list (owner may add or drop types at plan time).

**Verification.** Unit tests, census, in-game visual check on the local server with screenshots from the owner or an agent, independent review.

---

## M11-4 - Placed mob encounters  [draft]

**Intent.** The mine had no mobs. Encounters must be designed content that players meet.

**Expectation.** Encounters are placed at generation: patrol groups, ambushes, nests (spawner plus guards), mini-bosses, landmark guardians, wandering elites; vanilla mobs for Layer 1 plus Born in Chaos elites per the existing catalogue. Wake and leash rules, per-chunk and per-layer caps, no PvE in safe zones, respawn on daily reset only (optional slow trickle behind config, default off).

**Acceptance criteria.**
1. Census shows >= 1 encounter per 30-40 route blocks on Layer 1, with documented density per layer.
2. Caps hold: no chunk exceeds the cap; server tick stays within the documented budget with 4 simulated players near dense areas.
3. Safe zones remain hostile-free; no mob spawns on PvP rules (none exist).
4. Existing encounter runtime audit and protection tests pass.
5. Layer 1 is solo-survivable by a player with starter gear (checked by owner playtest, recorded as owner verdict).

**Scope.** Encounter placement and catalogue extension; reuse `encounter` package. Non-goals: boss design (M11-7), loot (M11-5).

**Dependencies and open decisions.** After M11-3. Open: cap values and wake radius, set from a tick measurement.

**Verification.** Unit tests, census, `/dawnmine` encounter audit, TPS and log on the local server, independent review.

---

## M11-5 - Mine-native loot and rarity tiers  [draft]

**Intent.** The mine had no loot. The owner wants mine-native rewards only: "you go to the mine to mine".

**Expectation.** Loot tables owned by the mod produce ores, raw ores, coal, gems, stone and mineral blocks, rails, torches, TNT and mine-tier tools. Tiers (common to boss) differ by ore grade, quantity and block variety, not exotic items. Sources: miner lockers, supply crates, ore carts with chests, dead-miner corpses, key-or-lever vaults, mini-boss drops. Placement is daily-random and bounded.

**Acceptance criteria.**
1. Census: >= 1 loot container per 40-60 route blocks and >= 1 vault per layer.
2. No loot table contains an item outside the approved mine-native list (test scans every table).
3. Higher tiers have strictly higher expected ore value (computed test).
4. No duplication loops, containers cleared on reset, locker and Dispatch privacy tests pass.
5. Key-and-vault pairs are always solvable (reachability test from the hub).

**Scope.** Loot tables, container placement, vault logic. Non-goals: custom items, pack-specific loot, economy changes outside existing progression.

**Dependencies and open decisions.** After M11-3. Open (non-blocking): confirm "ordinary mining supplies" list.

**Verification.** Unit tests on tables and solvability, census, live opening of containers on the local server, independent review.

---

## M11-2b - Ore system v2  [draft]  (runs after M11-1, parallel with M11-3)

**Intent.** Ore was too scarce and invisible. A miner must always see ore, and rich finds must exist.

**Expectation.** Noise-shaped veins (6-40 blocks) with per-layer ore tables over the full 32-ore catalogue, always partly wall-exposed; motherlode caverns (2-4 per layer); hidden ore behind cracked walls; native drops and break rules unchanged; Netherrack ceiling unchanged.

**Acceptance criteria.**
1. Census: every ~20 route blocks has >= 1 exposed vein; exposed ore share meets per-layer targets (proposal L1 >= 6%, L2 >= 10%, L3 >= 14%, confirmed after baseline).
2. Every catalogued ore appears in its declared layers on every fixed seed.
3. Netherrack default places none; enabled stays under the ceiling.
4. Ore-break audit (60-case) still passes.
5. No ore inside protected volumes (hub, safe zones).

**Scope.** Replace `OrePlacementPlanner` use for v2 terrain. Non-goals: new ores, drop changes.

**Dependencies.** After M11-1 (needs terrain); uses M11-2 route metrics for exposure guarantee. **Verification.** Unit tests, census, ore-break audit, live walk, review.

---

## M11-6 - Layer 2: The Deep Delve  [draft]

**Intent/Expectation.** Deepslate layer with flooded galleries, fungal grottos, a huge vertical shaft, motherlodes, mixed-role mob groups, nests with mini-bosses and puzzle vaults, the Lift Hub checkpoint with shortcut rails up.

**Acceptance criteria.** Same census gates as Layer 1 with Layer 2 targets; >= 3 routes down; Lift Hub shortcut reaches a previous checkpoint; zero unreachable cells; flooding never traps a player (reachability test counts swimmable and air-pocket cells); owner playtest accepts.

**Scope.** Layer data, landmarks and encounter/loot tables for Layer 2. Non-goals: Layer 3, boss. **Dependencies.** After M11-5 and owner's Layer 1 playtest. **Verification.** Unit tests, census, live walk and log, review.

---

## M11-7 - Layer 3: The Chaos Depths and team boss  [draft]

**Intent/Expectation.** Lava lakes with bridge routes, collapsing ledges, quartz and top-tier ore, Born in Chaos threats, and a bottom arena boss that needs a coordinated team (at least two roles), with rare-tier mine-native haul and the Heart checkpoint.

**Acceptance criteria.** Layer census gates; boss mechanics cue-readable and bounded (no unbounded mob accumulation); lava contained (no spreading); multiplayer playtest shows a group clears materially more reliably than solo (recorded owner verdict, no claim from code tests); Netherrack ceiling intact.

**Dependencies.** After M11-6. **Verification.** Unit tests, census, multiplayer playtest card, TPS and log, review.

---

## M11-8 - Builder tooling and authored landmark overrides  [draft]

**Intent.** The owner's builder is non-technical and knows only "build X". Tooling must hand-hold.

**Expectation.** `/dawnmine builder` lists the next structure to build in plain words, with a particle-marked box showing exact size and anchor; `save` validates (size, connectors present, no forbidden blocks) and stores it; errors explained in plain language; progress ("3 of 8 done"); `test` previews it in the mine layout; `reset` restores the procedural version. Authored landmark loads if saved, with procedural fallback (same pattern as `dawnmine:spawn_hub`).

**Acceptance criteria.**
1. A first-time user, following only the in-game text, completes one landmark end to end (usability walkthrough recorded with a naive tester or simulated naive-user script; flagged `unverified` if no person tests it).
2. Every error path gives a plain-language fix; no command syntax or file path required of the builder.
3. Missing or invalid authored file falls back to procedural with an admin-visible notice, never a failed run.
4. Operator-only; builder can be a separate permission without full op (proposal: a `builder` tag or perm level, confirmed at plan time).

**Non-goals.** Authoring any specific landmark; changing hub behaviour. **Dependencies.** After M11-3; needs owner sign-off for extending the authored-structure exception. **Verification.** Unit tests, GameTests for save/load/fallback, usability walkthrough, review.

---

## M11-9 - Cutover, cleanup, tuning and tester release  [draft]

**Intent.** Make v2 the only mine and remove v1.

**Expectation.** v2 becomes default; v1 `topology`, `SegmentGeometry`, sealer, noise-roughness code and obsolete tests removed; docs (README, architecture, adventure contract) updated; tuning from census and owner playtest; full verification pass; tester prerelease per AGENTS authorization.

**Acceptance criteria.** Full unit and GameTest suites pass (or any non-runnable suite is explicitly `unsupported`, as GameTestServer is in this sandbox); fresh reset on the local test server clean; census targets all met; no references to removed code; changelog and upgrade note (existing runs are discarded at the next reset, no data migration needed for terrain; Dispatch untouched); independent review.

**Non-goals.** Production deployment, stable release. **Dependencies.** After M11-7 and M11-8. **Verification.** Commands, census, live reset log, review.
