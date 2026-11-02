# Public Mines Milestones

Target: Rustic Craft 2 2.2.2, Minecraft 1.21.1, NeoForge 21.1.248, Java 21. Codex is product owner and owns the player outcome, milestone contracts, and acceptance decision. Devin implements. An independent reviewer challenges every delivery before it is accepted. No dates or estimates are set.

The desired experience is specified in [Progression.md](Progression.md), with durable decisions in [Decision.md](Decision.md) and player value/success measures in [Business.md](Business.md). Milestones are dependency ordered. M0 proves a safe technical foundation; it does not redefine the product as a compatibility project. All later gameplay milestones remain required to deliver the mine.

## M0 — Standalone mod foundation and pack compatibility

**Outcome:** A separate server-side mod builds and loads beside the exact target pack. The team has reproducible evidence for every pack ore and the actual Born in Chaos, recipe, and equipment integration surfaces needed by later work.

**Scope:** Java 21 / NeoForge 21.1.248 project, unique mod identity, server-safe entry point, clean build, exact-pack dedicated-server smoke test, artifact hashes, Born in Chaos 1.7.6 registry/entity report, ore-tag versus installed-mod reconciliation (including untagged and unusual ores), and a pack-grounded recipe/equipment progression appendix.

**Non-goals:** No mine world or gameplay, entity spawning, loot changes, reset, storage, economy balancing, faction-mine edits, or production deployment.

**Depends on:** None.

**Proof:** Clean build from source; dedicated server reaches ready with the exact pack; no new side/dependency errors; reports can be reproduced from pinned artifacts; every ore is mapped or an explicit unresolved item is listed; source/config diff proves the factions mine is untouched; independent review and evaluation pass.

**Risk:** Pack artifacts and runtime behavior can differ from catalog/static data. Preserve the shared worktree and distinguish inspected facts from runtime/player-playtested behavior.

## M1 — Shared run lifecycle and in-game admin onboarding

**Outcome:** One authoritative persistent run is shared by all players, has an explicit owned-area and recovery boundary, and a new operator can set up and validate it using friendly in-game help and commands.

**Scope:** Evidence-backed dimension/region choice and disposable prototype; saved run ID, seed, date, lifecycle state, bounds, generated frontier and owned-area records; safe surface/arrival locations; restart/partial-init recovery; `/publicmine help`, status, setup/validation, supported config get/set, permission matrix, idempotent initialization and safe recovery. Keep private admin diagnostics separate from targeted player guidance. No client mod required for server commands.

**Non-goals:** Rich procedural geometry, ore placement, mobs/rewards, private instances, scheduled reset execution, unsafe arbitrary world edits, or faction-mine changes.

**Depends on:** M0.

**Proof:** Two players resolve the same active run/state; restart or interrupted initialization recovers without duplication or clearing unrelated areas; new operator completes setup/validation from in-game help; permission tests prove unauthorized mutation is denied; invalid/unbounded settings are rejected; messages reach only the intended players/admins; no faction-mine state changes.

**Risk:** Dimension registration, permission APIs, and cleanup boundaries must be verified against the pinned NeoForge server. Keep setup transactional and never report success before persistence is confirmed.

## M2 — Connected procedural mineshaft exploration

**Outcome:** The shared run feels like a large generated mine to explore, with multiple connected routes, discoveries, recognizable algorithmic landmarks, and frontier expansion ahead of players.

**Scope:** Deterministic seeded topology, mineshaft corridors, branches, loops, dead ends, caverns, pockets, landmarks and checkpoint alcoves; algorithmic block geometry/palettes; persisted segment IDs and frontier; bounded scheduled generation; safe path/connectivity validation and recovery. Build geometry in code. No prefab structure files or predefined buildings are required.

**Non-goals:** Pack-specific ore selection/rates, mob encounter tables, Dispatch persistence, final reset scheduling, authored building assets, or unrelated world generation.

**Depends on:** M1 ownership, bounds, and run identity.

**Proof:** Fixed seed/config yields repeatable topology; all main routes connect and are traversable; optional branches are explicitly connected or marked dead ends; restart/interruption never duplicates or disconnects segments; generation stays in the owned bounds, respects measured tick/chunk budgets, and cannot strand players.

**Risk:** Procedural liquid/terrain may create disconnected paths, unsafe pockets, or server stalls. Validate a plan before block mutation and expand in bounded work.

## M3 — Every pack ore in depth-based ecology

**Outcome:** Players can discover and mine every ore in the actual Rustic Craft 2 pack in meaningful depth tiers, using native blocks, tools, and drops.

**Scope:** Reviewed machine-readable ore catalog from M0 plus runtime tags; explicit IDs for untagged/special ores and their variants; deterministic depth/region mapping and bounded pockets; quartz in Chaos Depths; contained lava as a readable environmental hazard; server configuration for optional Netherrack, off by default and only at extreme depth with a hard ultra-rare ceiling.

**Non-goals:** Global ore-generation/loot changes, replacing native mining rules, new ore types, or a Netherrack progression requirement.

**Depends on:** M0 catalog and M2 regions/resource placements.

**Proof:** Machine-readable comparison has no unclassified pack ores; generated-run samples contain each configured ore in its declared region/depth; native harvest requirements and normal drops work; default config places no Netherrack; enabled Netherrack respects the hard rarity/depth ceiling; reload or modlist mismatch is surfaced rather than silently dropping ores.

**Risk:** Tags and registry names are imperfect ore taxonomies; verify unusual blocks individually and bound resource yield to protect the server economy.

## M2c — Mine isolation and fair-play rules

**Outcome:** Players explore the intended generated routes rather than entering through natural caves, mining through the mine boundary, or reshaping terrain to bypass mobs. Ore mining and floor-placed torches remain supported.

**Scope:** Replace or seal natural cave intersections inside the mine-owned region; generate a continuous external bedrock shell with only intentional entrances; include shell and replaced terrain in persisted bounds, cleanup, restart recovery, and reset; server-side permissions allow breaking only catalogued mine ores and placing only ordinary or soul torches on floor surfaces; prevent common terrain-altering mechanics from bypassing these protections; preserve sanctioned permission-gated admin recovery.

**Non-goals:** No changes to natural caves or terrain outside mine-owned bounds, faction mine, global mining/placement rules, mob AI, prefab buildings, or new ore types.

**Depends on:** M2b world generation (#4) for bounded generated geometry; M3 ore catalog (#5) for the complete allowlist; both issues in M2c must pass before M4 encounters (#6) are accepted.

**Proof:** Dedicated-server fixtures show no unintended cave connection; every shell face and corner blocks survival mining from outside and escape from inside; approved entrances and internal routes remain explorable; every catalogued ore retains native harvest/drop behavior; all non-ore breaks and non-torch placement are denied; representative terrain bypass mechanics fail; faction-mine and other-dimension behavior is unchanged; recovery/reset remains idempotent and confined to owned bounds.

**Risk:** Protection hooks may interact with modded ore harvest events, explosions, pistons, or fluids. Use the verified M3 catalog, fail closed for unknown blocks, and validate the actual target pack.

## M4 — Born in Chaos encounters and mine-only rewards

**Outcome:** The mine becomes more dangerous with depth through readable, varied encounters, and fighting is a viable way to contribute useful mine resources.

**Scope:** Reviewed Born in Chaos candidates by region/depth; bounded encounter placement and population; mine-origin tracking, cleanup/recovery, and additional depth-tiered ore/salvage rewards for player kills; verified counterplay, attack cues, and route/hazard interactions. Integrate through public registry IDs and mine-scoped events.

**Non-goals:** Global Born in Chaos config, AI, natural spawn rates or loot changes; unreviewed bosses; unbounded mob scaling; difficulty based only on health/damage inflation.

**Depends on:** M0 artifact/registry report, M2 encounter locations, and accepted M2c issues #22 (mine boundary shell) and #23 (interaction protections). M3 informs reward tiers and hazards.

**Proof:** Only verified entities spawn; depth adds different threat combinations; a real player breaking infected ore and fighting any resulting mob is tested if that hook is used; mine-only kills grant only bounded configured bonuses; outside behavior/drops remain unchanged; populations and cleanup stay bounded after restart/reset.

**Risk:** Confined-space special abilities can be unfair or destabilizing. Candidate entities remain excluded until safe player counterplay is demonstrated.

## M5 — Shared checkpoints, safe travel, and personal Dispatch

**Outcome:** Players can navigate the active shared mine, choose when to return, and securely bank a useful haul in persistent themed storage larger than an Ender Chest.

**Scope:** Safe generated checkpoint pads available to everyone after discovery; travel between surface and generated checkpoints; clear enter/exit/resume feedback; a private persistent Foreman's Dispatch (at least 54 slots as the product baseline) accessible at checkpoints and matching surface depot; transactional deposit/retrieve and offline/restart recovery.

**Non-goals:** Player-specific mine instances, shared/guild locker contents, item trading, paid capacity, arbitrary teleport commands, or custom death penalties.

**Depends on:** M1 ownership/player state and M2 generated stations. M7 must preserve Dispatch data.

**Proof:** Multiple players can only access their own Dispatch; inventory-full, disconnect, failed teleport, restart, and reset paths do not delete or duplicate items; teleports land on clear safe pads; only checkpoints in the current generated run are usable.

**Risk:** Inventory mutation and teleport must be atomic or recoverable. A checkpoint is run-local; the personal Dispatch is durable.

## M6 — Durable gear progression and expedition economy

**Outcome:** Useful mining, PvE, and exploration contributions produce bankable inputs for real equipment upgrades. Each upgrade solves one proven earlier problem while the next depth introduces a different tactical challenge.

**Scope:** Versioned graph of pack ores, sources, recipes, tools, armor, hazards, mine rewards, yields, sinks, and capability counters; gap handling where the pack has no suitable item; measured resource bounds; player-facing discovery of useful next upgrades; repeated-run validation of persistence and changed capability.

**Non-goals:** Speculative gear trees, permanent depth skips, automatic grants, paid progression, currency, global recipe/loot edits, or extra death loss. Use the smallest namespaced mine item/recipe only when verified pack content cannot supply a needed counter.

**Depends on:** M0 real item/recipe evidence, M3 ore sources, M4 PvE/exploration rewards, and M5 banking.

**Proof:** Every progression edge has artifact-backed source/cost/output and bounded yield; mining, PvE, and exploration each matter without forcing every player into every role; a group banks and crafts/equips an upgrade, returns in a later run, handles an earlier challenge better, and encounters a different new counter; materials and gear survive reset/restart/relog with no duplication route.

**Risk:** Recipe names or static definitions do not prove obtainable play behavior. Only playtested capability changes count as progression evidence.

## M7 — Configurable morning reset and safe evacuation

**Outcome:** Each morning the shared mine closes, warns only its occupants, safely evacuates them, and creates one fresh run while preserving durable player progression and unrelated worlds.

**Scope:** Configurable morning hour and explicit server timezone; product default 06:00 server-local time; warnings; entry lock; safe ejection; mine-owned terrain/entity/drop cleanup; new run ID/seed; idempotent date scheduling; downtime catch-up and interrupted-reset recovery; operator status/recovery.

**Non-goals:** Wiping player inventories, equipment, progression materials, Dispatch contents, faction-mine state, or other worlds; changing the reset to a wipe of player progression.

**Depends on:** M1 ownership and persisted lifecycle, M2 generation/cleanup, M5 safe travel/persistence, and M6 progression invariants.

**Proof:** Just-before/at/after schedule, online players, offline downtime, restart during each reset phase, and retry cases produce exactly one fresh run per configured date; players end safely outside; all durable data and faction fixtures remain intact; no new entry occurs during cleanup.

**Risk:** Reset must be chunk/tick bounded and transactional. Effective time and timezone must be visible to operators and players.

## M8 — Full-pack playtests, challenge balance, and operator readiness

**Outcome:** Real players can learn early depth, make meaningful mine-or-fight/bank-or-push decisions, improve durable capability, adapt to new threats, and coordinate to reach the deepest intended region. Operators can run and recover the feature without source-code knowledge.

**Scope:** Repeatable solo, group, and multi-day dedicated-server runs; early/mid/deep playtest cards; route, cue/reaction, mining, combat, exploration, banking, upgrade, death/recovery, group-size, tick/memory/entity, and yield observations; targeted player/admin communications; faction-mine regression; operator setup/help/status/config/validation/recovery exercise; tune only from evidence; publish a tester-focused GitHub prerelease with the verified JAR, changelog, and clear tester instructions.

**Non-goals:** Production-server deployment or stable release; claiming subjective fun/addictiveness from code tests; redesigning unrelated pack/faction features.

**Depends on:** M0–M7 and M2c evidence.

**Proof:** Same deep encounter compared at solo and coordinated group size; evidence shows teamwork materially changes success/effort through simultaneous tactical demands rather than only inflated stats; at least two daily runs demonstrate a useful upgrade and a new counter; mining, PvE, and exploration are viable contributors; players can understand cues and risk; record player choices/short feedback about whether they want another run; all persistence/reset/admin/faction/performance gates pass. Release readiness is withheld if the evidence is insufficient; the tester prerelease contains only the verified target-compatible JAR and includes its checksum, changelog, install/run instructions, and known limitations.

**Risk:** Fun and team dependence are empirical claims. Do not infer them from code, a solo developer run, or one successful server boot.

## Current execution status

- M0 (#1) and M1 (#2, #12) are merged.
- M2a (#3) is in implementation. M2b (#4) remains open.
- M2c is GitHub milestone #10 with issues #22 (depends on #4) and #23 (depends on #4 and #5); both gate M4a (#6).
- M3–M8 remain required. All changes require independent review and verified evidence before merge.
- M8 includes a tester-focused GitHub prerelease after acceptance evidence passes. Production-server deployment is not authorized.
## Issue contract map

| Milestone | Outcome issues |
| --- | --- |
| M0 Foundation and pack compatibility | [#1](https://github.com/DurdeuVlad/rusticcraft-public-mines/issues/1) |
| M1 Shared lifecycle and admin onboarding | [#2](https://github.com/DurdeuVlad/rusticcraft-public-mines/issues/2), [#12](https://github.com/DurdeuVlad/rusticcraft-public-mines/issues/12) |
| M2 Procedural topology and generation | [#3](https://github.com/DurdeuVlad/rusticcraft-public-mines/issues/3), [#4](https://github.com/DurdeuVlad/rusticcraft-public-mines/issues/4) |
| M3 Ore ecology | [#5](https://github.com/DurdeuVlad/rusticcraft-public-mines/issues/5) |
| M2c Mine isolation and fair-play rules | [#22](https://github.com/DurdeuVlad/rusticcraft-public-mines/issues/22), [#23](https://github.com/DurdeuVlad/rusticcraft-public-mines/issues/23) |
| M4 Born in Chaos and mine rewards | [#6](https://github.com/DurdeuVlad/rusticcraft-public-mines/issues/6), [#7](https://github.com/DurdeuVlad/rusticcraft-public-mines/issues/7) |
| M5 Checkpoints, travel, and Dispatch | [#8](https://github.com/DurdeuVlad/rusticcraft-public-mines/issues/8), [#9](https://github.com/DurdeuVlad/rusticcraft-public-mines/issues/9) |
| M6 Gear progression and expedition economy | [#13](https://github.com/DurdeuVlad/rusticcraft-public-mines/issues/13) |
| M7 Morning reset | [#10](https://github.com/DurdeuVlad/rusticcraft-public-mines/issues/10) |
| M8 Playtests and operator readiness | [#11](https://github.com/DurdeuVlad/rusticcraft-public-mines/issues/11) |
