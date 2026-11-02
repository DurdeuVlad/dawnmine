# M2a architecture: deterministic procedural topology

Scope: issue #3 (deterministic procedural topology). Produces the connected,
seeded plan M2b turns into geometry. No block placement, ore tables, mob
spawns, or authored structures live here.

## Component map

```text
com.rusticcraft.publicmines.topology   (pure Java, no Minecraft imports)
├── TopoPos / TopoBounds               value types for the plan's coordinate space
├── NodeKind / NodeFeature / EdgeKind  node roles and feature flags
├── TopoNode / TopoEdge                graph records (edges canonically ordered)
├── TopologyProfile (+ RegionSpec)     data-driven layout + depth bands, JSON-backed
├── TopologyPlanner                    deterministic generator (seed + profile + bounds)
├── TopologyValidator -> TopologyReport  pre-mutation gate (all invariants)
├── TopologyPlan                       immutable plan: nodes, edges, main path,
│                                      canonical bytes, SHA-256 fingerprint
├── TopologyProfiles                   server datapack reload listener
└── MineTopology                       bridge: MineRunData bounds + level height -> TopoBounds
```

Everything above `MineTopology`/`TopologyProfiles` is pure Java, so the whole
generator is unit-testable without a running game. The two boundary classes
are the only place Minecraft types appear.

## Determinism

`TopologyPlanner.plan(seed, profile, bounds)` is a pure function. A single
`java.util.Random` is consumed in a fixed sequence; hash sets are used only
for membership, never iteration. `TopologyPlan.canonical()` serializes
nodes/edges/features in sorted order; `fingerprint()` is its SHA-256.
The fingerprint and plan size are persisted in `MineRunData`
(`topologyProfile`, `topologyFingerprint`, `topologyNodes`,
`topologyEdges`; schema 2) so a replan after restart or `/publicmine setup
validate` proves byte-for-byte equality rather than asserting it.

## Shape of a plan

- One wandering main route (`m000` entrance under the arrival pad, `mNNN`
  terminus in Chaos Depths) built from ~8-node legs steered toward rotating
  sector excursion targets, scored by distance to existing route nodes and
  a density penalty so the route sweeps the field instead of ray-mining.
- Depth bands are the profile's ordered regions: `upper` -> `middle` ->
  `chaos` (Nether-themed, deepest). Node Y interpolates each band and is
  clamped to the band and the vertical corridor margin.
- Off-route structure: branch chains (`bNNN-i` caves) that end as flagged
  dead ends or reconnect as loops; pockets (`pNNN`) on short spurs hosting
  resource-field locations for M3; caverns (route upgrades), checkpoints
  (on-route at region exits), landmarks (deterministically named).
- Every degree-1 node except the entrance is marked `DEAD_END`, so
  validation can tell intended endings from broken connections.

## Validation gate

`TopologyValidator` runs before any world mutation and fails the run (status
FAILED, human-readable last error) on: duplicate ids/positions, out-of-bounds
nodes or corridor margins, unreachable nodes, main-route discontinuity or
region backtracking, unmarked dead ends, off-route checkpoints, and the
configured generation budget (node count + total corridor length).

`MineManager.initialize()` runs plan + validate after recording bounds and
owned volumes and before READY. `validateReadyRun` replans and compares the
persisted fingerprint. A broken profile or bounds therefore can never
produce a half-placed mine - topology code places zero blocks regardless.

## Data-driven progression

Profiles live at `data/publicmines/topology_profiles/*.json`, loaded by a
`SimpleJsonResourceReloadListener` (datapack-tunable, reloadable). The
bundled `standard.json` carries upper/middle/Chaos bands; an invalid file is
logged and skipped. If nothing valid loads, a compiled-in identical copy is
the last-resort guard (a test pins JSON == builtin so fallback output is
byte-identical).

## Known boundaries (honest)

- `frontierRegions` stays 0; frontier growth and per-segment persistence are
  M2b scope.
- The plan is recomputed from seed, not persisted node-by-node; the
  fingerprint receipt detects profile/bounds drift after restart.
- Route footprint is a wandering corridor system (~350-700 blocks of the
  2048-block field plus ~60 side nodes); wider coverage is tuned through the
  profile and M2b frontier expansion, not claimed here.
- The flat GameTestServer does not load the mod's custom dimension, so its
  lifecycle tests cover safe-failure behavior there. The real custom-dimension
  initialize/READY/restart-replan path is recorded in
  `docs/reports/M2a-dedicated-server-validation.md`; JVM tests cover the pure
  planner and validator.

## Verification

- `gradlew.bat clean test`: deterministic replans, randomized graph/bounds
  sweeps, connectivity, route/dead-end/region invariants, malformed profiles,
  profile-work budgets, persistence migration/receipt validation, and lifecycle
  edge cases.
- `gradlew.bat build`: compiles the mod and its test sources.
- `gradlew.bat runGameTestServer`: exercises lifecycle failure-safe behavior;
  the custom-dimension happy path is covered by the dedicated-server report
  above.
- `docs/reports/M2a-topology-snapshots.txt`: golden canonical dumps for
  seeds 42, 1337, 20261102, regenerated by `TopologySnapshotsTest`; a plan
  change regenerates the file and fails once so the diff is reviewed.
