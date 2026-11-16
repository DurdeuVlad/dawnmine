# M14d Issue Draft — Reset takes too long, with too little visibility while it runs

## Planning metadata
- Issue type: Performance / UX
- Priority: Medium
- Milestone: M14 — Per-layer customization and depth-based chaos escalation
- Assignee: Unassigned
- Target date: Not set

## Intent

The owner observed a `/dawnmine reset now` taking roughly 10 minutes during live play; this
session's own verification resets of the same 64-chunk shell took 40+ minutes on at least two
occasions. Whether or not that specific wall-clock time is "too slow" is partly a product
decision (see Dependencies and open decisions), but the current experience gives an admin or
waiting player almost no visibility into *why* it's taking that long or how much is left, beyond
manually polling `/dawnmine reset status`.

## Expectation

A reset's actual duration is understood (not guessed at) and, if it can be safely shortened
without risking server tick-lag, is shortened; and whatever time it does take, players and
admins get clear, proactive progress information without needing to poll a status command
themselves.

## Context that code cannot infer

Confirmed by reading the code, a reset is two separately-throttled phases, each deliberately
conservative to avoid a tick-lag spike - this throttling is very likely intentional
server-friendliness, not an oversight, so this issue should not simply "turn the numbers up"
without checking that reasoning first:
- **CLEANING** (`MineDailyReset.java:64-67,292-358`): restores each of the shell's existing
  chunk columns to a freshly-regenerated baseline, budgeted to `RESTORES_PER_TICK = 4` columns
  and `CLEANUP_BUDGET_NANOS = 15_000_000L` (15ms) of work per server tick, followed by
  `SETTLE_TICKS = 15` idle ticks before moving on.
- **FINALIZING** (`MineGenerator.java:59-74`): the new run's whole-shell pregeneration, budgeted
  to `PREGEN_ISSUE_PER_TICK = 8` new chunk-generation futures issued per tick,
  `PREGEN_SCAN_PER_TICK = 256`, `OPERATIONS_PER_TICK = 4096`, and `SLICE_BUDGET_NANOS = 5_000_000L`
  (5ms) per tick.
- For a 64-chunk-radius shell (`128 x 128 = 16384` columns, matching every `/dawnmine census`
  and reset log line observed in the M13 session), both phases process the *entire* shell, not
  just the area a player is near - this is consistent with the mod's existing "whole-run
  pregeneration before entry opens" design (referenced directly in `LootSites`'s own M12d
  report), not a bug, but it does mean reset time scales with the full configured
  `mineRegionRadiusChunks`, not with how much of the mine anyone will actually visit that run.
- `/dawnmine reset status` already reports live progress (phase, columns checked, blocks
  restored, entities discarded) - this session used it extensively via RCON polling. The gap is
  that nothing *pushes* this to players/admins periodically; today's existing proactive
  messaging (`MineMessages.notifyAdmins`, `MineDailyReset.java:387-388`) only fires once, on
  completion, not during the wait.
- The M13 session's own verification found the *actual* per-column cost is substantial on this
  hardware (observed ~150ms/column average across a full reset), not merely an artifact of the
  tick budgets - so even a throughput increase has a real floor set by how expensive one
  column's cave/ore/landmark computation (`CaveTerrain`, `OreField`, `Landmarks`) actually is.
  Whether that per-column cost itself has easy optimization headroom (e.g. redundant
  recomputation, cache misses) is unknown and should be profiled, not assumed, before concluding
  the fix is "just raise the per-tick caps."

## Scope

- Profile one full reset (CLEANING + FINALIZING) on the local test server to establish where
  wall-clock time actually goes: tick-budget throttling itself (i.e. the process is
  CPU-available but artificially paced) vs. genuine per-column compute cost (i.e. the process is
  CPU-bound even within its budget window). This determines which of the options below is worth
  doing - do not skip this and guess.
- If throttling is the dominant cost and headroom exists without risking tick lag (verified by
  watching actual server tick time during a test reset, not assumed), raise the relevant
  per-tick budgets, and strongly consider exposing them as admin-tunable via the same
  `ConfigKeys`/`/dawnmine config` mechanism M14b is already introducing, rather than hardcoding
  a new fixed value - different hardware will have different safe headroom.
- Regardless of the throughput finding, add proactive progress messaging during a reset: a
  periodic admin (and optionally player-facing, if any player is mid-evacuation/waiting)
  broadcast of percent-complete and current phase, reusing `MineMessages.notifyAdmins` at a
  sensible interval (e.g. every 10% or every N seconds, not every tick) rather than only on
  completion.
- Investigate whether the FINALIZING phase's full-shell pregeneration could be scoped down
  (e.g. prioritizing the hub/arrival area and the three main descent routes first, with the
  rest of the shell completing in the background) so entry can reopen sooner for the parts of
  the mine a player reaches first - this is explicitly a bigger, riskier change than the above
  two bullets and should only be attempted if the profiling step shows it would materially help,
  not pursued reflexively.

## Non-goals

- No change to the daily *scheduled* reset's own timing/warning behavior
  (`resetHour`/`resetMinute`/`resetWarningMinutes`, already configurable) - this issue is about
  how long the reset itself takes and how visible that is, not when it starts.
- No change to what gets reset (world content, baseline restoration correctness) - purely
  performance and communication during an already-correct process.
- Does not reduce the default `mineRegionRadiusChunks` shell size as a workaround - that is a
  product decision about mine size, not a performance fix, and is explicitly out of scope here.

## Dependencies and open decisions

- Independent of M14a-M14c; can be picked up in any order relative to them.
- Open decision: is there an owner-stated target duration for a reset (e.g. "under 5 minutes"),
  or is "clearly communicated while it happens" sufficient even if the duration itself doesn't
  change? This issue was opened from the owner directly reporting the *duration* as a problem
  ("the mine is reseting for a good 10 minutes"), so a felt target should be confirmed with the
  owner before the profiling step's findings are judged "good enough" or not.
- Open decision: whether the progress-messaging addition should also reach players waiting
  outside the mine (not just admins), and in what form (chat message, boss bar, action bar) -
  needs a product call, not an implementer's guess.
- Product owner: Durdeu Vlad-Ioan. Implementer: the next agent assigned this issue. Reviewer:
  independent.

## Acceptance criteria

1. A profiling pass's findings (throttle-bound vs. compute-bound, and by how much) are recorded
   in the PR, with real numbers from an actual reset on the test server - not an assumption.
2. If a safe throughput increase is found and applied, a before/after reset duration comparison
   is recorded, alongside confirmation (via server tick-time monitoring during the test reset)
   that no new tick-lag was introduced.
3. An admin (and, if the open decision above resolves that way, any waiting player) receives at
   least one proactive progress update during a reset that takes longer than some short
   threshold (e.g. 30 seconds) - not only a final completion message.
4. `/dawnmine reset status`'s existing on-demand reporting is unchanged in correctness (only the
   new proactive messaging and/or throughput are new).
5. The owner's felt-duration concern is explicitly addressed in the PR: either the duration
   measurably improved, or a clear reason is given for why it is already near its safe floor,
   with the new progress visibility as the mitigation.

## Verification

Manual timed reset runs on the local test server, before and after any throughput change, with
server tick-time (`/dawnmine` or vanilla tick-time diagnostics) monitored throughout to confirm
no lag spike was introduced. `./gradlew test` full suite (no behavioral regression to reset
correctness). Owner playtest of the new progress messaging during a real reset.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals,
affected ownership, dependencies, commands/results, and residual risks - including the profiling
numbers that justified whatever was or wasn't changed about throughput.
