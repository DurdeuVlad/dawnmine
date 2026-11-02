# M6 — durable equipment and depth progression economy

## Intent

Every expedition's mining, PvE, and exploration contributes to lasting
capability: players bank finds on the surface, craft better equipment with
the pack's own recipes, and use it to push deeper on a later shared run.
Each upgrade solves a real earlier-depth problem while a deeper region
introduces a different problem that needs skill, preparation, or
coordination. The design contract is `Progression.md`; the issue is #13.

## Components

| Piece | Path | Role |
| --- | --- | --- |
| Progression graph data | `src/main/resources/data/publicmines/progression/standard.json` | Versioned datapack catalog: materials, upgrades, blocked nodes, discovery notes |
| Graph runtime | `src/main/java/com/rusticcraft/publicmines/progression/ProgressionGraph.java` | `SimpleJsonResourceReloadListener`; parses, bounds, validates, fingerprints |
| Player/admin surface | `/publicmine progression` in `command/PublicMineCommand.java` | Player-facing upgrade guidance; staff integrity diagnostics |
| GameTests | `run/MineProgressionGameTests.java` | Persistence across a new run, reward de-dup, tool-gate delta, graph sanity |
| Runtime audit | `run/MineProgressionRuntimeAudit.java` + `publicmine_audit_progression` | Opt-in exact-pack verification: registry/recipe resolution, tool gates, bank persistence across restart + simulated reset, reward de-dup |

## Data model

Three node kinds, all in one schema-1 file (hard bounds: 64 materials,
32 upgrades, 16 blocked nodes):

- **Material** — a pack item id plus tier (`upper` / `middle` / `chaos`,
  the ore-catalog region ids), `coDrops` for pooled drops (the eight gem
  ids of `jewelry:gem_vein`), `outsideMine` for materials whose only
  verified source is outside the mine, bounded `sources`, `sinks`,
  `boundedYield` text, and `evidence` (audit artifact references).
- **Source** — `mining` (`block:<ore-id>`, must resolve to an ore-catalog
  entry whose `native_drop_items` contain the material), `pve`
  (`reward:<tier>` for the configured mine reward table or
  `entity:<entity-id>` for a named mob drop), or `exploration` (bounded
  free text, e.g. overworld structure chests).
- **Sink** — `recipe` (pack recipe id + expected output item, resolved
  against the live `RecipeManager` by `verifyRecipes`) or `mechanic`
  (bounded descriptive use: fuel, enchanting, trading, tech input).
- **Upgrade** — id, tier, activities (subset of mining/pve/exploration),
  output item, recipe id, human-readable inputs, `solves`,
  `doesNotSolve` (must differ), `verification` (`verified` or
  `candidate`), `evidence`.
- **Blocked node** — item, `neededFor`, `reason`, `evidence`. A verified
  pack/config gap that is recorded, never silently worked around.

The snapshot is hashed (SHA-256 over canonical node content) so a graph
change is visible in logs and receipts as `fingerprint`.

## Ownership and boundaries

- The graph is read-only data + reporting. It adds **no** items, recipes,
  loot changes, rewards, automatic grants, permanent unlocks, per-player
  instances, or death penalties.
- `MineRunData` continues to own only run-scoped state (entities,
  rewards claims, checkpoints, ore origins). Player inventories,
  surface chests, and the Dispatch locker (issue #9, landed in M5b)
  are outside mine-owned reset state — the GameTest proves a
  `beginNewRun` transition and save/load round trip leave them
  untouched.
- The graph cross-references but does not own: ore catalog entries
  (`OreCatalog`), reward tiers (`MineRewardCatalog`), encounter
  compositions (`MineEncounterCatalog`), topology resource fields
  (`TopologyProfile`).
- BiC entity/loot/global behavior is untouched. The infected-diamond-ore
  termite route is recorded as a **blocked node**: the pack procedure is
  verified (M0/M4a) but the ore planner only rolls resource-kind
  entries, so encounter-kind infected ores are never placed in the
  configured mine today. No fabricated source was added.

## Failure semantics

- Malformed files fail closed at parse (`JsonParseException`), are
  logged per resource, and do not poison the snapshot's other files.
- Nodes referencing unregistered items are **kept** but listed under
  `Completeness.unavailableItems` — on the dev runtime (no pack mods)
  14 mod item ids are flagged this way; the graph still reports.
- A reward tier that resolves to `null` is only flagged as
  `unknownRewardTiers` when the material's own item *is* registered —
  otherwise the missing item (already reported) explains the skipped
  reward (MineRewardCatalog drops rewards whose item cannot register).
- `verifyRecipes(recipeManager, registries)` is a hard runtime check:
  every recipe sink and every upgrade recipe must exist and produce the
  declared output. It runs inside the exact-pack audit and is callable
  from server validation.

## Runtime surfaces

- `/publicmine progression` — for every tier, the upgrades whose output
  item is registered in *this* runtime (`reachableUpgrades`), plus a
  one-line "solves" hint; operators additionally get the fingerprint
  summary, full integrity report, and blocked-node list. `/publicmine
  help` and `/publicmine status` point at it.
- `publicmine_audit_progression [prepare|verify|all]` — registered only
  under `-Dpublicmines.enableMineProgressionAudit=true`, op level 4,
  restricted to a disposable world `publicmines-m6-progression-audit`.
  `prepare` initializes run A, banks materials in an overworld chest
  (the same player-owned persistence the Dispatch locker provides), and resolves the
  whole graph + recipes + tool gates. `verify` (after a real server
  restart) confirms the chest survived, simulates the next daily run
  (fresh run id — the behavior issue #10 will schedule), re-checks the
  bank and run-scoped cleanup, and proves reward claims cannot
  duplicate across retry or reset.

## What is deliberately not here

- No permanent depth unlocks; every day remains the same fresh shared
  expedition — progression is durable *equipment*, not keys.
- No new mine recipes/items: no verified pack gap required one. The
  blocked nodes document the three gaps that exist.
- Deepest-tier coordinated-group proof is deferred to M8; the graph
  marks the netherite/dark-metal chain `candidate` and records why.
