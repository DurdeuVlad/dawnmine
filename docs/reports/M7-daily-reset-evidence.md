# M7 daily-reset evidence

Verification for issue #10 on branch `codex/m7-daily-reset`, Minecraft 1.21.1 /
NeoForge 21.1.248 / Java 21, disposable copy of the Rustic Craft 2 pack
(`Rustic Craft Main Server Testing/_pm-m7-verify`, mod `publicmines-0.1.0.jar`).

## Test results

- `./gradlew test` — **BUILD SUCCESSFUL**; all JUnit tests pass, including
  `ResetScheduleTest` (before/at/after occurrence, warning thresholds and
  dedupe, DST gap/overlap, minute rounding) and the schema-9
  `MineRunDataTest` cases (transaction save/load, malformed reset fields
  fail closed, future schema preserved verbatim, synthetic-key dedupe
  regression).
- `./gradlew jar` — BUILD SUCCESSFUL; jar deployed to the pack server.
- `./gradlew runGameTestServer` — 23 GameTests ran; **all 7 `m7_daily_reset`
  tests pass** (`scheduledOccurrenceStartsExactlyOneResetAndLocksEntry`,
  `downtimeCatchUpRunsExactlyOneReset`,
  `uninitializedRunConsumesOccurrenceWithoutCleaning`,
  `interruptedCleanupResumesFromPersistedCursor`,
  `manualResetIsSingleFlightAndStatusIsOperatorFacing`,
  `haltedResetStaysHaltedUntilStaffRearm`,
  `warningOffsetsAreConfigurableAndBounded`; the live-cycle test skips
  cleanly because GameTestServer does not load the custom dimension).
  Three required baseline tests still fail — `minebreaktorchandsafezone
  eventsareprotected`, `malformedreadyreceiptblocksarrivalpadrepair`,
  `everycatalogoreusesnativeplayerbreakbehavior` — all dimension-dependent
  tests already documented as GameTestServer-environment failures in
  `docs/reports/M4a-encounter-validation.md`; they are unrelated to M7.

## Dedicated-server evidence

Disposable pack copy; schedule temporarily set to `23:51 Europe/Bucharest`
with warnings `[3,1]`; restored to defaults afterwards.

### Scheduled reset with warnings (m7-boot3.log)

- `23:47:59` — `Warned 0 mine occupant(s) about the 2026-11-02T23:51 reset (3 minute(s) remaining).`
- `23:49:59` — same message, `1 minute(s) remaining.` (occupant-only channel; zero occupants online)
- `23:51:00` — `Daily reset 2026-11-02T23:51 started (occupants evacuated: 0).` — fires at the exact scheduled second.
- `23:51:05` — `finished cleanup: 16384 columns checked (16335 skipped, 566 blocks restored, 1 block entities removed, 2 entities discarded).`
- `daily reset ... complete: new run 2026-11-02-3 ..., 4353 ms`
- RCON after completion: inside diamond block → air, inside chest → air,
  zombie absent, loose drop absent; outside gold block intact; overworld
  emerald marker intact; `Run: 2026-11-02-3 status: READY`, `Entry: open`,
  `last completed reset: 2026-11-02T23:51`, `Transaction: none`.

### Downtime catch-up (m7-boot3.log)

Changing the schedule to `23:51` while the previous day's `2026-11-01T23:51`
occurrence was unconsumed caused exactly one catch-up reset at boot
(`new run 2026-11-02-2`), after which the real `2026-11-02T23:51` occurrence
fired once at its second (`new run ...-3`). One reset per occurrence.

### Interrupted CLEANING resume (m7-boot4.log)

Server stopped, `publicmines_run.dat` patched to `resetPhase=CLEANING`,
`resetDue=m7-midclean-2351`, `resetCursor=5000`, `status=RESETTING`, restart:

- `Resuming daily reset phase CLEANING for occurrence m7-midclean-2351 at column 5000.`
- `finished cleanup: 16384 columns checked (11335 skipped, 564 blocks restored ...)` — resumed from the persisted cursor, not column 0.
- `complete: new run 2026-11-02-4` — one new run, transaction cleared.
- The subsequently fired `2026-11-02T23:51` occurrence created `...-5`,
  proving the synthetic key did not consume the real occurrence.

### Interrupted FINALIZING resume (m7-boot5/6/9.log)

Patched `resetPhase=FINALIZING`, `resetDue=m7-finalizing`/`m7-fin-elapsed`/
`m7-fin-v2`, `status=RESETTING`:

- `Resuming daily reset phase FINALIZING for occurrence m7-fin-v2 at column 16384.`
- `daily reset m7-fin-v2 complete: new run 2026-11-03-14, 16384 columns checked, 606 ms` —
  exactly one replacement run through the normal initialization path, and the
  real column count now reports correctly (previously `0 columns checked`
  because the resume path skipped rebuilding session cleanup state; the fix
  also keeps the finalize-time entity purge armed after restart).
- First pass (pre-fix jar) exposed a diagnostic bug — elapsed printed
  `210170550 ms` because the wall-clock start was not re-armed on resume;
  fixed by initializing `resetStartedNanos` on `onServerStarted`/drive entry
  and verified at `606 ms`/`3561 ms` on later boots.

### Halted reset and operator retry (m7-boot7.log)

Patched `status=FAILED`, `resetPhase=CLEANING`, `resetCursor=8000`,
`resetAttempts=3`:

- Two `/publicmine reset status` polls ~60 s apart both showed
  `CLEANING 'm7-halted' - 8000 columns, 3 attempt(s)` — the halt was not
  auto-bypassed; `Entry: closed` throughout.
- `/publicmine reset retry` → `Daily reset m7-halted re-armed by staff at column 8000` → cleanup resumed (`8337 skipped, 564 restored`) → `new run 2026-11-03-10`, `Transaction: none`, `Entry: open`, `status: READY`.

### Manual/synthetic reset does not re-fire a consumed occurrence

Completing a synthetic-key transaction used to overwrite `lastResetDue`,
making the already-consumed occurrence look pending; the scheduler then
reset it a second time (observed runs `-5`, `-9`, `-11`). Root cause
fixed: `completeDailyReset` only records `lastResetDue` for real
scheduled-occurrence keys (`ResetSchedule.isOccurrenceKey`); regression test
`syntheticResetKeyDoesNotConsumeTheScheduledOccurrence` added and passing.

Post-fix live verification (m7-boot8/9.log):

- `/publicmine reset now` → `Daily reset manual:... complete: new run
  2026-11-03-12` — exactly one run, `lastResetDue` stayed `2026-11-02T23:51`,
  and no scheduled refire followed.
- `m7-fin-v2` FINALIZING resume → `new run 2026-11-03-14`,
  `lastResetDue` stayed `2026-11-02T06:00`, `Transaction: none` — the first
  synthetic completion on the fixed build produced no spurious reset at all.
- Schedule restore to `06:00` fired exactly one legitimate catch-up for the
  genuinely unconsumed `2026-11-02T06:00` occurrence (`new run ...-13`),
  then converged: `next: 2026-11-03 06:00`, `Transaction: none`.

Additional hardening in the same pass: `MineManager.enter` and
`travelToCheckpoint` now lock on `resetInProgress()` itself, not only on
`RESETTING` status, so a crash persisted as `READY`+`FINALIZING` cannot
admit a player mid-transaction.

## Config restored

After verification the disposable server config was returned to
`resetHour=6`, `resetMinute=0`, `resetTimeZone=server`,
`resetWarningMinutes=[15,5,1]` and `publicmine reset status` confirmed the
effective schedule `06:00` and next occurrence.

## Shipped-artifact sanity (m7-boot10.log)

The exact jar committed to the PR branch (`16da03b`) was redeployed and
rebooted after all verification above. Boot loaded
`status=READY runId=2026-11-03-14 resetPhase=NONE lastReset=2026-11-02T06:00`
with no spurious reset (00:40 local is before today's 06:00 occurrence and the
yesterday-06:00 occurrence is already consumed). `publicmine reset status`
reported:

    Schedule: 06:00 Europe/Bucharest (next: 2026-11-03 06:00 ... (in 5h 19m))
    Warnings: [15, 5, 1] minute(s) before, mine occupants only
    Last completed reset: 2026-11-02T06:00
    Transaction: none

## Limitations / residual risk

- **No connected client**: multi-occupant evacuation and carried-inventory
  preservation are covered by the GameTest mock `ServerPlayer`
  (`liveResetEvacuatesCleansOwnedVolumesAndRebuildsRun`, which runs the full
  cycle where the dimension loads) but not by a real client session; all
  live runs here logged `occupants evacuated: 0`. The evac path reuses the
  normal leave/teleport logic verified in earlier milestones.
- **Dispatch lockers** (M5b, merged during this work): locker contents live
  in the separate `publicmines_dispatch` SavedData keyed by player UUID and
  deliberately independent of the run record (`DispatchData`); the locker
  interaction is menu/session-based with no placed block entities, so the
  owned-volume cleanup cannot touch it. Verified by construction and code
  review; no live locker-contents-across-reset snapshot was taken.
- **Faction mine**: the cleanup allowlist is dimension-qualified
  (`publicmines:public_mine_v2` only) and cannot address other dimensions;
  no faction-mine regions exist in the disposable copy to diff against, so
  isolation is guaranteed by construction rather than by a live diff.
- GameTestServer cannot load `publicmines:public_mine_v2`, so the three
  pre-existing dimension-dependent failures above remain environment
  failures; the full cleanup cycle is proven on the pack server logs cited
  here.
- **Post-review fix (b8781ac)**: independent review identified that
  offline-at-reset players could log back in inside rebuilt terrain with no
  evacuation pass remaining (entombment/suffocation risk). Fixed with a
  `PlayerLoggedInEvent` hook: `MineManager.evacuateUnsafeMineLogin` moves
  any player in the mine dimension out via the normal leave path whenever a
  reset transaction is in flight, the run is not enterable, or their saved
  feet/eye position is inside solid blocks. Covered by
  `loginInsideMineDuringOrAfterResetIsEvacuated` (live legs run on the pack
  server; the batch stays green in the flat GameTest world via the same
  skip-guard as the other live tests). Remaining residue: a player already
  online inside the mine during a retry-backoff window stays until the next
  evacuation pass (~5s max) — entry stays locked so no new entrant can
  appear mid-backoff, and new logins are caught by the login hook.
