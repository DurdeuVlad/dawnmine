# Milestones

Target environment: Rustic Craft 2 2.2.2, Minecraft 1.21.1, NeoForge 21.1.248, Java 21. No dates or effort estimates are assigned. Product owner is the server owner; Devin implements; owner reviews gameplay policy and release. The milestones are outcome boundaries in strict dependency order. Feature work begins after the M0 compatibility evidence is available.

## M0 — Standalone mod foundation and pack compatibility

**Outcome:** A separate mod project builds and loads on a dedicated server beside the target pack; the implementation team has verified the exact ore and Born in Chaos integration surfaces.

**In scope:** Java 21/NeoForge 21.1.248 scaffold, metadata, server-safe entry point, build configuration, dedicated-server smoke run, artifact and registry audit for Rustic Craft 2 2.2.2, BiC 1.7.6 entity ID report, common ore tag coverage report, and explicit list of untagged ores discovered.

**Out of scope:** Any mine gameplay, custom world, loot changes, entity spawning, or faction-mine edits.

**Dependencies:** None.

**Proof:** Clean build; dedicated server loads the mod with the pack; no client-only class-load failures; checked-in compatibility report names the exact artifacts, all ore IDs or a reproducible completeness path, BiC entity IDs, and unresolved hazards.

**Risks/open decisions:** Actual local mod jars may be absent despite the pack catalog; retrieve only the exact approved artifacts needed for inspection. Resolve the mod ID/package with a uniqueness check.

## M1 — Shared mine run and safe world lifecycle

**Outcome:** The server maintains exactly one active, persistent mine run shared by all players, isolated from unrelated world/faction-mine state.

**In scope:** Architecture decision for a custom dimension or isolated bounded region; persisted run seed, run ID/date, generation frontier, owned-area records, depth/region state, lifecycle recovery, and safe entry/exit coordinates. Build a disposable technical prototype before settling world placement.

**Out of scope:** Rich procedural layout, ore generation, encounter tables, locker GUI, and production daily reset policy.

**Dependencies:** M0 artifact and server evidence.

**Proof:** Two players observe identical run identity and state; restart restores the active run without duplicating generated state; cleanup can identify only mine-owned areas; ejection coordinates are safe and outside the mine.

**Risks/open decisions:** Dimension registration and dynamic extension APIs must be checked against NeoForge 21.1.248. Set practical world bounds and per-tick generation budgets from measured results.

## M2 — Crystal Hollows-style procedural exploration world

**Outcome:** A large connected shared map gives players multiple routes and regions to discover, and expands ahead of exploration during the run.

**In scope:** Deterministic seeded block geometry for main routes, branch tunnels, open caverns, loops, dead ends, ore fields, recognizable landmarks, environmental hazards, safe checkpoint alcoves, themed upper/deep/Chaos regions, and persisted frontier generation. Geometry is generated from algorithms and block palettes; no prefab buildings or structure files are required.

**Out of scope:** Specific ore balance, mob encounters, daily reset completion, or player-specific instances.

**Dependencies:** M1.

**Proof:** Same run seed yields the same generated topology; multiple players see the same cells; each generated route is traversable and connects to an existing route; generation is restart-safe, bounded, and stays inside mine-owned limits; forced failure does not leave players trapped.

**Risks/open decisions:** Random layouts can create disconnected routes or unsafe liquid paths; generation needs deterministic validation and chunk-load/tick budgets.

## M3 — Complete modpack ore catalog and depth ecology

**Outcome:** Every ore in the target pack can be found by mining in a deliberate depth tier, with native block behavior and drops preserved.

**In scope:** Runtime discovery through common ore tags plus explicit IDs from the M0 audit, registry reload handling, reviewed ore-to-tier data, all block variants that belong to each ore, ore pocket distribution, Nether ore placement in Chaos Depths (including quartz), and contained lava around suitable resource areas.

**Out of scope:** Replacing mod ore loot tables, modifying global ore generation, or adding new ores.

**Dependencies:** M0 catalog and M2 regions.

**Proof:** A machine-readable catalog comparison shows every pack ore is mapped; a generated-run report shows each catalogued ore in its intended tier; native pickaxe requirements and normal drops still apply; no ore is silently omitted after reload or modlist change.

**Risks/open decisions:** Poorly tagged or specialized mod ores may require explicit block IDs and dimension-specific handling. All added ore rates require review against the server economy.

## M4 — Born in Chaos depth encounters and rewards

**Outcome:** Exploration becomes more dangerous with depth, using verified BiC mobs and mine-only ore rewards.

**In scope:** Encounter sets by region/depth using M0-verified entity IDs, controlled spawn points and population budgets, elite/rare encounter opportunities where the artifact safely supports them, persistent mine-origin tracking, depth-tiered ore/salvage rewards on player kills, and cleanup at reset.

**Out of scope:** Changes to Born in Chaos configuration, natural spawn rates, AI, or loot outside the mine; forced spawning of unverified bosses.

**Dependencies:** M0 artifact report and M2 regions. M3 tier definitions inform rewards.

**Proof:** Only verified entities spawn; tier pressure rises with depth; player kills inside the mine receive only configured bonus rewards; natural entities outside the mine keep their original behavior/drops; entity cap and unload/cleanup work after restart and reset.

**Risks/open decisions:** Special BiC abilities can be dangerous in confined caves. Start from a reviewed candidate list and leave any unsafe entity out until an explicit test supports it.

## M5 — Checkpoints, teleport travel, and Foreman's Dispatch

**Outcome:** Players can move through the shared run without losing orientation or inventory capacity, then access their haul outside.

**In scope:** Safe checkpoint pads integrated into generated terrain; enter from surface; return to surface; resume at any checkpoint already generated in the active shared run; every player can use those same stations; checkpoint discovery/status feedback; personal, persistent Dispatch inventory with at least 54 slots as the proposed baseline; deposit and retrieve at every checkpoint and the matching surface depot; locker isolation by player.

**Out of scope:** Item trading, shared guild storage, paid capacity, arbitrary world teleport commands, and any death/inventory-loss policy change.

**Dependencies:** M1 owned area and player state; M2 checkpoint placement. M6 reset must preserve this data.

**Proof:** Multiple players can independently access only their own locker; items survive relog, restart, and mine reset; deposit/retrieve has no duplication or deletion on full inventory; every teleport lands at an unblocked safe pad; checkpoints not yet generated or belonging to a stale run cannot be selected.

**Risks/open decisions:** Final capacity is owner-reviewed; preserve inventory transactionally on disconnects and full-storage/failed-teleport cases.

## M6 — Configurable morning cycle and complete mine reset

**Outcome:** Each scheduled morning starts one fresh shared run and evacuates players without losing personal data or touching unrelated worlds.

**In scope:** Configurable reset hour/timezone with proposed default 06:00 server-local time; advance warnings; safe ejection; disable entry during reset; clear mine-owned terrain/entities/loose items; new run ID and seed; idempotent date-based execution; overdue reset after server downtime; operator status/recovery visibility.

**Out of scope:** Wiping player inventory, Dispatch lockers, faction-mine state, or other dimensions/regions.

**Dependencies:** M1 ownership/reset transaction; M2 generator; M5 teleport and locker persistence.

**Proof:** Test just-before/at/after schedule, restart while reset is pending, server downtime crossing the reset time, and multiple players inside. Exactly one reset is recorded for the date; players end in the safe surface area; new run differs; lockers/inventories/faction-mine fixtures remain intact.

**Risks/open decisions:** Owner must confirm reset clock and timezone before release. Reset must be chunk/tick bounded and recover transactionally if interrupted.

## M7 — Pack-level playthrough, balance, and operations readiness

**Outcome:** The complete mine is reliable and enjoyable in the real modpack and maintainable by server operators.

**In scope:** Full dedicated-server playthrough with multiple players; performance and memory profiling; ore-rate and mob-pressure tuning; reset and recovery playbook; configs/defaults; admin status/recovery commands if justified by observed failure cases; final player-facing entry, depth, reset, and storage instructions.

**Out of scope:** Public mod distribution, modpack integration of unrelated features, changes to the factions mine, and unapproved economy changes.

**Dependencies:** M0-M6.

**Proof:** Written run-through evidence covers entry, exploration, all tiered ores, Chaos hazards, BiC encounters, kill rewards, checkpoints, storage, restart, and daily reset. Dedicated server remains stable at agreed concurrency; factions-mine regression path is unchanged; operators can inspect and recover a failed reset without editing files by hand.

**Risks/open decisions:** Set concurrency and acceptable tick-time budgets with the server owner before calling the system release-ready.

## Current execution

- M0 is ready for Devin to start after its GitHub issue and private repo are created.
- Later milestones remain planned, not implemented. M0 evidence can revise technical proposals without changing the accepted player experience.

## Issue contract map

- M0: docs/issues/M0-foundation.md — https://github.com/DurdeuVlad/rusticcraft-public-mines/issues/1
- M1: docs/issues/M1-shared-run-lifecycle.md — https://github.com/DurdeuVlad/rusticcraft-public-mines/issues/2
- M2: docs/issues/M2a-procedural-topology.md (#3) and docs/issues/M2b-world-generation.md (#4)
- M3: docs/issues/M3-ore-ecology.md — https://github.com/DurdeuVlad/rusticcraft-public-mines/issues/5
- M4: docs/issues/M4a-bic-encounters.md (#6) and docs/issues/M4b-mine-rewards.md (#7)
- M5: docs/issues/M5a-checkpoints-travel.md (#8) and docs/issues/M5b-dispatch-locker.md (#9)
- M6: docs/issues/M6-daily-reset.md — https://github.com/DurdeuVlad/rusticcraft-public-mines/issues/10
- M7: docs/issues/M7-pack-validation.md — https://github.com/DurdeuVlad/rusticcraft-public-mines/issues/11


