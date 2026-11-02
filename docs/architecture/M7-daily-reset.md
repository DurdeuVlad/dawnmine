# M7 architecture: daily morning mine reset

Scope: issue #10 (reset the shared public mine every morning). The mine is one
shared run for all players in the dedicated `publicmines:public_mine_v2`
dimension. M7 adds a scheduled, persisted reset transaction that warns mine
occupants, locks entry, evacuates everyone inside, restores recorded
mine-owned terrain to the generated baseline, and creates exactly one
replacement run per scheduled occurrence. Faction-mine state, unrelated
dimensions, player inventories, and personal storage are never touched.

## Scheduled occurrences and idempotency

`ResetSchedule` is pure wall-clock math over `resetHour:resetMinute` in
`resetTimeZone` (default `06:00`, zone `server` = host default). One
"occurrence" exists per local date; `ZonedDateTime.of` resolves DST gaps
forward and overlaps to the earlier offset, so every date yields exactly one
deterministic occurrence. `lastOccurrence(now)` is the dedupe input; its key
is the occurrence's local wall time (`key(...)` = ISO local datetime).

Every evaluation (once per second on the server tick) does at most:

1. deliver due advance warnings to current mine occupants;
2. compare `key(lastOccurrence(now))` with persisted `lastResetDue`;
3. when different and a run exists, open the reset transaction once.

Downtime crossing the scheduled time makes the most recent occurrence "due"
exactly once; an `UNINITIALIZED` run consumes the key without a transaction so
later initialization is never surprised by stale catch-up work. Because the
check is "last occurrence key != last completed key", repeated evaluation
never duplicates a scheduled reset.

`completeDailyReset` records `lastResetDue` **only when the completed key is
a real scheduled occurrence** (`ResetSchedule.isOccurrenceKey`). Manual resets
(`manual:<LocalDateTime>`) and staff-injected transactions complete without
touching the dedupe record — otherwise an already-consumed occurrence would
look pending and the scheduler would immediately reset it a second time.
Conversely a scheduled occurrence that is still genuinely pending when a
manual reset finishes (e.g. the manual reset was in flight across 06:00)
still fires as the designed catch-up.

## The persisted transaction

`MineRunData` schema 9 persists five fields so a restart anywhere in the
transaction converges to exactly one outcome:

- `resetPhase` — `NONE | CLEANING | FINALIZING` (`ResetPhase`),
- `resetDue` — the occurrence key being reset,
- `resetCursor` — chunk columns already restored, checkpointed as it advances,
- `resetAttempts` — bounded retry counter (max 3, then halt for staff),
- `lastResetDue` — the dedupe record above.

`beginDailyReset` opens `CLEANING` together with `RunStatus.RESETTING` in one
dirty save, so a crash can never observe an unlocked cleanup. `markStatus`
failure paths (`FAILED` + attempts >= 3) stay halted: the tick driver refuses
to re-mark `RESETTING` until `/publicmine reset retry` clears the attempts.

`MineManager.onServerStarted` deliberately does **not** fail a `RESETTING`
run the way it fails an interrupted `INITIALIZING` run; `MineDailyReset`
resumes the persisted phase instead (logging the resume for operators).
`MineManager.initialize()` refuses to create a run while `CLEANING` owns the
mine; `FINALIZING` drives the normal initialization steps so the replacement
run receives the next run sequence and a fresh seed, then
`completeDailyReset` clears the transaction and records the occurrence.
Run-scoped state (checkpoints, safe zones, ore provenance, encounter claims,
return points, generation progress) resets through the normal `beginNewRun`
path; player-side data is not part of the run record.

## Bounded owned-volume cleanup

Cleanup is strictly allowlisted by the persisted `OwnedVolume` records that
dimension-qualify to `publicmines:public_mine_v2` (`active_region` and
`arrival_pad` today). Columns are enumerated from volume bounds in the same
deterministic order the cursor counts.

- On-disk existence probes (`ChunkMap.read`, window of 256) run ahead of the
  cursor; a column absent from disk is already at baseline and skipped for
  free — cleanup never generates a missing chunk just to clear it.
- Existing columns are restored in bounded slices (`RESTORES_PER_TICK` = 4
  columns, 15 ms budget): `baselineAt` reproduces the
  `BoundedFlatLevelSource`/`MineShellGeometry` output — bedrock shell faces
  and roof where applicable, configured flat layers, air elsewhere — then
  removes block entities and recomputes heightmaps/light for changed
  sections. Changed chunks are queued for saving.
- Non-player entities and loose `ItemEntity` drops inside owned volumes are
  discarded during cleanup and again during a settle window and finalize, so
  late-loading entity sections cannot smuggle anything through.
- Blocks, block entities, and entities outside recorded volumes — including
  the overworld and the factions mine — are never visited.

A cleanup/finalize failure records an attempt and backs off 100 ticks; after
3 attempts the run halts `FAILED` with staff-facing guidance until
`/publicmine reset retry`.

## Players: warnings, entry lock, evacuation

`resetWarningMinutes` (default `[15,5,1]`, bounded 1–720 minutes, at most 8
entries, deduplicated; `none`/empty disables) defines the occupant-only
warning offsets. `MineMessages.notifyMineOccupants` delivers them only to
players currently inside the mine dimension; staff diagnostics go through
`notifyAdmins` and the log separately.

Entry is locked as soon as `beginReset` marks `RESETTING`; `manager.enter`
reports `NOT_READY` with a "mine is resetting" explanation while any phase is
in flight. Every cleanup/finalize tick re-runs `evacuateOccupants`, which
reuses the normal leave flow so players land on the recorded overworld
surface exit (falling back to spawn/return-point logic) **with inventories,
equipment, and personal storage untouched** — the transaction never opens a
player inventory.

## Operator surface

- `/publicmine reset status` — schedule, next reset, warnings, last
  completed occurrence, live phase/cursor/attempts, failure detail.
- `/publicmine reset now` — starts a manual reset (synthetic key, never
  consumes a scheduled occurrence).
- `/publicmine reset retry` — re-arms a halted transaction.
- `/publicmine status` and `/publicmine help` surface reset state and entry
  lock alongside the run summary; all reset subcommands require operator
  permission.

## Files

- `run/MineDailyReset.java` — scheduler, warnings, evacuation, bounded
  cleanup, finalize/retry drivers, operator status.
- `run/ResetSchedule.java` — occurrence/warning math (unit-tested).
- `run/MineRunData.java` — schema 9 reset fields and fail-closed loading.
- `run/ResetPhase.java`, `run/RunStatus.java` — persisted phase/status.
- `command/PublicMineCommand.java`, `command/ConfigKeys.java`,
  `config/PublicMineConfig.java` — `/publicmine reset` subtree and
  `resetHour`, `resetMinute`, `resetTimeZone`, `resetWarningMinutes`.
- `PublicMines.java` — server-start resume and per-tick wiring.

Evidence: `docs/reports/M7-daily-reset-evidence.md`.
