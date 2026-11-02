# M1 architecture: shared run lifecycle and admin surface

Scope: issues #2 (shared run + safe ownership/recovery boundary) and #12
(in-game admin onboarding). Later milestones (generated geometry, ore ecology,
encounters, checkpoints/Dispatch, reset scheduling, multiplayer proof) are out
of scope but the model is designed to carry them.

## Component map

```text
com.rusticcraft.publicmines
├── PublicMines                 mod entry; registers config, MineManager, commands
├── run/
│   ├── MineRunData             SavedData: one authoritative run record (NBT)
│   ├── OwnedVolume             labelled, dimension-qualified cleanup bounds
│   ├── RunStatus               UNINITIALIZED / INITIALIZING / READY / FAILED
│   └── MineManager             lifecycle orchestration: init, validate, recover,
│                               entry/exit safety, ownership checks (4 states)
├── config/
│   └── PublicMineConfig        NeoForge SERVER config with hard bounds
└── command/
    ├── PublicMineCommand       Brigadier /publicmine tree, permission gates
    ├── ConfigKeys              per-key validation, friendly errors + examples
    └── MineMessages            audience-targeted message helpers
```

## Placement: dedicated dimension `publicmines:public_mine`

The mine is its own dimension (datapack `dimension` + `dimension_type`,
overworld-like rules, 384-block height, minimal flat generator for M1).

Why not an overworld region: the shared mine must be reset and cleaned without
ambiguity. A dimension gets its own world directory
(`world/dimensions/publicmines/public_mine`), its own chunk ownership, and a
hard boundary no faction build can accidentally inherit. Cleanup is "the
recorded volumes in our dimension" — never a heuristic over the overworld.

The arrival pad is the only authored block structure: a 5x5 stone platform at
`[0, 63, 0]` placed by setup and re-verified by validate/recover. Everything
else is deferred to M2 generation code, which will work inside the recorded
active region using the run seed.

## Persistence: SavedData run record

`MineRunData` is a single `SavedData` (`publicmines_run.dat`) holding:

- `runId` (`dayId-N`, e.g. `2026-11-02-1`), `runSequence`, `dayId`, `seed`
- `status` + `lastError` (human-readable, operator-facing)
- `bounds` (active region in chunk coords) and a `frontierRegions`
  counter reserved for M2
- `arrival` + `surfaceExit` anchors
- `ownedVolumes[]` — the complete cleanup allowlist
- `returnPoints` — per-player surface positions for `/publicmine leave`

One record per server → every player resolves to the same active run identity.
`resolveRunFor(player)` returns that single record; there is no per-player
fork of the run.

## Lifecycle

```text
UNINITIALIZED --setup initialize--> INITIALIZING --all steps ok--> READY
     ^              |                                   |
     |              +-- step fails ------------------> FAILED --recover repair--> (re-init)
     |
  boot hook: INITIALIZING on load -> FAILED("initialization interrupted")
```

- `initialize()` is idempotent: a READY run returns "already initialized"
  without touching state; an UNINITIALIZED/FAILED run records all state changes
  (run record, bounds, volumes, anchors) before flipping to READY.
- Entry is locked unless status is READY *and* the arrival pad verifies safe.
- `onServerStarted` marks a run left INITIALIZING as FAILED — restart can never
  present a half-built run as usable.
- `recover check` reports state honestly; `recover repair` re-runs the
  initialization steps over the same recorded volumes.

## Ownership boundary

`OwnedVolume(dim, label, minBlock, maxBlock)` — cleanup may only ever touch
recorded volumes. M1 records two: `active_region` (128x128 chunk columns,
configurable) and `arrival_pad`. `isOwned(level, pos)` is the single check.
Nothing in the faction mine, the overworld, or any other dimension is ever
a candidate.

## Command surface and audience

| Command | Permission | Audience |
|---|---|---|
| `/publicmine help` | everyone | player help, hides staff block |
| `/publicmine status` | everyone | players: friendly yes/no + guidance |
| | op 2+ | full diagnostics (seed, bounds, volumes, day, reset schedule) |
| `/publicmine enter`/`leave` | in-game players | targeted message to the player |
| `/publicmine setup validate/initialize` | op 2+ | step list to invoker + log |
| `/publicmine config get/set` | op 2+ | private reply; mutations logged |
| `/publicmine recover check/repair` | op 2+ | private reply; mutations logged |

Console gets the staff view. Mutations are checked server-side with
`source.hasPermission(2)` (default vanilla operator threshold) — Brigadier
`requires` on the literal, so unauthorized callers get a clean "unknown
command" rather than a data leak, and the gate cannot be bypassed by calling
the command node directly.

Validation output separates severities: `[ok]` passes, `[note]` informational
(e.g. reset scheduling not yet implemented), `[!!]` real blockers with the fix.

## Config

`publicmines-server.toml` (NeoForge SERVER type, per-world serverconfig):

| Key | Bounds | Default |
|---|---|---|
| resetHour / resetMinute | 0-23 / 0-59 | 6 / 0 |
| resetTimeZone | IANA zone or `server` | `server` |
| netherrackEnabled | boolean | `false` |
| netherrackColumnInterval | 256..65536 | 256 |
| mineRegionRadiusChunks | 16..512 | 64 |

Spec-level validators are the floor; `ConfigKeys` re-validates in command
space and answers with valid range + a corrective example. Values persist
immediately (`backing.save()`), and changes say when they apply (next
scheduled reset / next run initialization).

## Server-side only

No client classes are touched. `neoforge.mods.toml` `displayTest` is
`IGNORE_SERVER_ONLY` so clients can connect without the mod; commands, help
text, and teleports are all vanilla server mechanics.

## Known boundaries (honest)

- `GameTestServer` cannot host custom dimensions (it bakes the FLAT preset);
  dimension-dependent tests assert fail-closed behavior there and run their
  real path via `/test` on a live dedicated server.
- Reset scheduling is recorded config only; the executor ships in M7.
- `frontierRegions` is a schema slot for M2; it stays 0.
- `enter`/`leave` were verified with server-side mock players, not real
  clients (vanilla `ServerPlayer.teleportTo` underneath).
- The permission model is two tiers (level <2 vs operator level 2+, console
  maps to level 4). The issue asks for distinct player/moderator/admin/console
  treatment; moderators today get the player experience except op-level
  elevation, which satisfies "default mutations to the operator threshold."
  A separate moderator capability would be new product surface — deferred.
- The dimension type allows beds and vanilla dark-spawn rules; monster
  encounter control is an M2/M4 design surface, not an M1 gate.
- `RESETTING`/`LOCKED` lifecycle states are intentionally absent until the
  M7 reset tooling that owns them exists; unknown persisted statuses load
  as FAILED, which `recover repair` can safely re-initialize.
