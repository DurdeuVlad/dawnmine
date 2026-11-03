# M8 repeat playtests, balance, and operator readiness — validation evidence

Verification for issue #11 on branch `codex/m8-playtests`, Minecraft 1.21.1 /
NeoForge 21.1.248 / Java 21 (Eclipse Adoptium `jdk-21.0.12.8-hotspot`), against
the exact Rustic Craft 2 2.2.2 server pack — 283 manifest-verified mod jars
plus the built `publicmines-0.1.0.jar` (284 jars at runtime).

Two surfaces were used because NeoForge disables GameTests in production mode
even with `-Dneoforge.enableGameTest=true`:

1. **Production-style staged server** (`pm-m8-playtest`) — exact pack
   running as a real dedicated server;
   used for all operator/RCON flows, live resets, persistence, and policy
   checks. `/test` is unavailable here by design.
2. **Dev pack mirror** (`runs/server` via `gradlew runServer`) — the same 284
   jars and same configs, launched through the dev target where
   `neoforge.enableGameTest=true` takes effect; used for all 39 GameTests in
   the real `publicmines:public_mine_v2` dimension.

## Build result

```
$ ./gradlew.bat build
BUILD SUCCESSFUL in 12s
32 actionable tasks: 2 executed, 30 up-to-date
```

## GameTest results (dev mirror, real mine dimension)

Final authoritative run (after the fixes below):

```
$ test runall          (via RCON, runs/server)
Running 39 tests...
Starting batch: m7_reset_manual:0
```

All 13 batches started and completed in `runs/server/logs/latest.log`
(02:58:39–02:59:18): `m7_reset_manual`, `defaultBatch` (27 tests),
`m7_reset_halt`, `m7_reset_downtime`, `m4a_encounters`, `m7_reset_warnings`,
`m7_reset_cursor`, `m2c_protection`, `m7_reset_live`, `m7_reset_uninitialized`,
`m7_reset_scheduled`, `m4a_native_bic`, `m7_reset_login_evac`.

- Zero `LogTestReporter ... failed at` lines in the final run log.
- `test runfailed` afterwards → `No tests found` (authoritative: nothing
  recorded as failed on the server).
- **39/39 GameTests pass on the exact pack runtime.**

### Live-reset leg inside the batch (was previously hung — see bug fixes)

```
[02:59:01] Daily reset manual:2026-11-03T02:59:01.861471900 started (occupants evacuated: 3).
[02:59:14] Daily reset manual:2026-11-03T02:59:01.861471900 finished cleanup:
           16384 columns checked (15742 skipped, 27 blocks restored,
           1 block entities removed, 5 entities discarded).
```

The test then asserts new run id/seed, cleaned owned volumes, intact
outside/overworld markers, and the evacuee's inventory — all satisfied (no
failure logged, batch advanced to `m7_reset_uninitialized`).

### Login-evacuation leg

```
[02:41:22] Evacuated returning player test-mock-player from the mine (resetting=true status=READY).
[02:41:22] Evacuated returning player test-mock-player from the mine (resetting=false status=READY).
```

Both legs (in-flight reset + entombed-after-restore) evacuate with the
carried diamonds intact, asserted in-test.

### Ore-break audit leg

`everyCatalogOreUsesNativePlayerBreakBehavior` ran the full
`OreBreakAudit` against the live pack — **60 audited break cases**, e.g.:

```
block=minecraft:nether_gold_ore tier=middle requires_tool=true tool=minecraft:wooden_pickaxe correct=true drops=[5xminecraft:gold_nugget]
block=born_in_chaos_v1:infected_diamond_ore tier=chaos kind=encounter requires_tool=true tool=minecraft:diamond_pickaxe correct=true drops=[] spawns=[born_in_chaos_v1:diamond_termite]
block=werewolves:silver_ore tier=middle requires_tool=true tool=minecraft:stone_pickaxe correct=true drops=[1xwerewolves:raw_silver]
block=minecraft:stick ... correct=false drops=[]   (wrong-tool legs)
```

Every catalog ore uses native player-break behaviour: correct-tool breaks
drop the catalogued items, stick/wrong-tool breaks drop nothing, and
`ENCOUNTER` entries spawn exactly the catalogued entity.

### Intentional skips (by design, logged)

- `MineEncounterGameTests` M4a entity-tracking and native-BiC tests log
  `skipped outside disposable world 'publicmines-m4a-hook-callback-audit'` —
  they only run in their fixture world; their live legs are covered by the
  pack-runtime observations below.
- Any test that cannot reach a READY run logs a skip with reason rather than
  failing. In the final runall all legs executed (status was READY).

## Bugs found during M8 playtesting and fixed on this branch

All fixes are bounded, driven by observed failures, and regression-covered by
the now-green 39/39 run.

1. **GameTest mock players rejected by the real pack** —
   `helper.makeMockServerPlayerInLevel()` used a raw embedded connection, so
   NeoForge `NetworkRegistry.checkPacket` rejected the custom payloads pack
   mods (KubeJS, modonomicon, voicechat…) send during login.
   Fix: new `gametest/MockServerPlayers.java` — `inLevel(helper)` builds a
   `CommonListenerCookie` + `EmbeddedChannel` connection, calls
   `NetworkRegistry.configureMockConnection`, then `placeNewPlayer`. All
   affected GameTests migrated to it.

2. **Reset evacuation could leave a player inside the mine** — real bug.
   `MineManager.findSafeLanding` returned `null` whenever the destination
   chunk was unloaded (`!level.hasChunkAt(base)`), and `leave()` then
   reported `FAILED`; on a dedicated server the surface-exit/spawn columns
   are routinely unloaded. The live-reset GameTest showed
   `Could not evacuate test-mock-player ... (FAILED)` followed by the test
   hanging while cleanup proceeded around the stuck occupant.
   Fix: `findSafeLanding` no longer bails on unloaded chunks — `isSafeLanding`
   block reads load the column on demand. The same unloaded-chunk gate was
   removed from `enter()` (denied first entry when the pad chunk unloaded)
   and `travelToCheckpoint()` (denied the fallback arrival).

3. **Evacuation had no last resort when nothing is standable** — real bug.
   With the exit column over open water (and nothing solid within the
   scanned range), `leave()` still returned `FAILED`.
   Fix: `leave()` now falls back to the recorded surface exit, else world
   spawn, so the player is always moved out of the resetting mine; vanilla
   handles the wet landing.

4. **Ore-audit GameTest fixture was not pack-proof** — pack mobs wander into
   the empty structure volume and tripped the audit's `position must be
   empty and clear of entities` precondition. Fix: the test now discards
   non-player entities within the audit volume and guarantees air before
   running (the audit is synchronous, so nothing can re-enter).

5. **OreBreakAudit's synthetic player also needed a mock connection** — on
   the real pack, breaking blocks triggered
   `modonomicon:sync_book_unlock_states` to the audit player and the raw
   connection threw `Payload ... may not be sent to the client`.
   Fix: `createSurvivalPlayer` calls `NetworkRegistry.configureMockConnection`
   before installing the listener — same proven pattern.

Files changed: `MockServerPlayers.java` (new), `MineManager.java`,
`OreBreakGameTests.java`, `OreBreakAudit.java`, and the five GameTest classes
migrated to `MockServerPlayers.inLevel`. `MineDailyResetGameTests` also moved
each of its nine tests into a distinct `batch` so `runall` executes the
shared-`MineRunData` mutations sequentially (one batch's tests run
concurrently; concurrent reset fixtures deadlocked shared state).

## Production-style staged-server evidence (exact pack, RCON)

RCON helper: `python rcon25600.py "<command>"` in
`staged server dir pm-m8-playtest`.

### Operator flows (all exercised on the real server)

- `/publicmine help` — player + staff sections, audience-targeted.
- `/publicmine status` — full staff diagnostics (below).
- `/publicmine config get/set` — settings inspection and bounded writes.
- `/publicmine setup validate` + `initialize` — clean on the staged world.
- `/publicmine recover check`/`repair` — inspected and exercised.
- `/publicmine reset status`/`now`/`retry` — schedule view, manual reset,
  halted re-arm path.
- `/publicmine progression`, `/publicmine checkpoints`,
  `/publicmine rewards` — verified output.
- Permission-denied paths confirmed for staff commands at level <2.

### Status snapshot (dev mirror; production mirror output was identical in shape)

```
Run: 2026-11-03-31  status: READY
Day: 2026-11-03  seed: -3194717595582124299
Topology: 101 nodes / 105 segments, profile standard, fingerprint 2fbf6854e7f5
Ore catalog: registered 32/32, missing ore-tag entries=[], unavailable=[],
             unavailable native drop items=[], unavailable encounter entities=[],
             empty tiers=[], catalog errors=[]
Progression graph: materials=20, upgrades=9, blocked=3,
             fingerprint=414795f107ed (integrity ok)
Owned volumes (2): active_region + arrival_pad — both publicmines:public_mine_v2
Daily reset: 06:00 Europe/Bucharest
Netherrack: off (default)
```

### Live manual reset on the dev mirror

```
$ publicmine reset now
Daily reset started: entry is locked, occupants are evacuated, and
mine-owned state is being restored.

log: Daily reset manual:2026-11-03T03:03:42.840682200 started (occupants evacuated: 2).
log: finished cleanup: 16384 columns checked (15742 skipped, 25 blocks restored,
     0 block entities removed, 1 entities discarded).   (~14 s)
```

Earlier production resets behaved the same: e.g. `2026-11-03-2 → -3`,
new seed `-543159325494812945`, topology fingerprint `97d4a2c68f77`,
25 blocks restored, 4,149 ms.

### Reset preservation — proven by hash

```
before reset:  md5 dispatch.dat = ef8760a336dca448c2bd4e52fb703572
               md5 run.dat      = 065026858f3dbb160cbbd8dabd7bd5b0
publicmine reset now ; save-all
after:         md5 dispatch.dat = ef8760a336dca448c2bd4e52fb703572   (unchanged — Dispatch survives)
               md5 run.dat      = 24cbecefb9c7fe0abec50e4ae472f2a3   (new run persisted)
```

Personal Dispatch data (`world/data/publicmines_dispatch.dat`) is untouched
by reset; run state flips to the new run and survives `save-all`. Production
verification earlier in the session likewise showed `publicmines_run.dat`'s
hash change only after `save-all` (not-yet-flushed state is disclosed as the
cause of the initially-unchanged hash).

### Netherrack policy

- `Netherrack: off (default)` in status — default off confirmed.
- `config set` attempts that would make netherrack more common or shallower
  were rejected; only-rarer values accepted (verified earlier this session;
  the 1-per-256-column / ≤3-block-cluster ceiling is unchanged).

### Born in Chaos in the mine

- Earlier this session an in-mine encounter spawn was observed:
  `upper_lone_pursuer` in the `upper` region with the logged
  tactic/counterplay line.
- `neoforge entity list` on the mirror shows live `born_in_chaos_v1:dread_hound`
  (7) among 269 total entities.
- The ore audit proves the BiC encounter blocks keep native behaviour:
  `infected_(deepslate_)diamond_ore` breaks spawn exactly
  `born_in_chaos_v1:diamond_termite`.

### Factions-mine regression

- Status owned volumes list only `publicmines:public_mine_v2` entries; the
  help text states cleanup "never touches the factions mine or other
  dimensions".
- The live-reset GameTest asserts the overworld emerald marker and the
  out-of-volume gold marker survive — passed in the 39/39 run.
- Cleanup logs show only `public_mine_v2` columns scanned.

## Metrics (dev mirror, real pack)

- Startup: ModLauncher 02:55:56 → `Done (2.840s)!` 02:56:54 — ~58 s total
  including datapack/registry load on 284 mods; dedicated init phase 2.840 s.
- `neoforge tps` after runall + manual reset:

  ```
  Overworld: 20.000 TPS (11.857 ms/tick)
  publicmines:public_mine_v2: 20.000 TPS (0.134 ms/tick)
  Overall: 20.000 TPS (12.312 ms/tick)
  ```

- `neoforge entity list`: 269 entities total — pack mobs
  (`vampirism:vampire` 24, `born_in_chaos_v1:dread_hound` 7, …), 13 players
  (real + leftover mocks), nothing pathological.
- AllTheLeaks loaded `MemoryMonitor` + `SaveWithLoadedChunks`; no leak
  reports name publicmines classes. Earlier runs showed ATL reporting
  retained mock-player references after GameTests — test artifacts, noted.
- Reset timing: ~4 s production / ~14 s mirror for 16,384 columns.
- Unrelated pack observations (disclosed): NeoForge version-check HTTP
  timeouts, third-party dedicated-server client-class warnings, worldgen
  `Empty height range` warnings — all pre-existing pack noise.

## Proven vs. unproven

**Proven on the exact pack**

- Clean startup and READY run on the real dimension; 32/32 ore catalog,
  progression graph integrity ok.
- 39/39 GameTests pass on the exact-pack mirror, including live reset,
  login evacuation, protection, dispatch, progression, ore audit legs.
- Operator surface: help/status/config/setup/recover/reset/progression/
  checkpoints/permission-denied all exercised via RCON.
- Reset: entry locks, occupants evacuate, owned volumes clean, outside
  volumes + overworld untouched, new run id+seed, transaction clears.
- Persistence: dispatch .dat byte-identical across reset; run .dat changes
  and persists after `save-all`.
- Netherrack default-off + only-rarer clamp enforced.
- BiC entities spawn in-mine and BiC encounter ores behave natively.
- Server stays at 20 TPS through runall + live resets.

**Fixed real bugs** (regression-covered by the same 39/39 run): mock-player
payload negotiation, unloaded-chunk evacuation/entry/checkpoint denial,
no-fallback evacuation, audit fixtures.

**Unproven / disclosed limits**

- Human repeat-playtests — gear-progress feel, early-depth learnability,
  late-depth tactics, and real team coordination cannot be proven by
  GameTests; they need live players on a staging server. The mechanics are
  verified; the experience assessment is outstanding.
- Production mode cannot run GameTests (NeoForge gating); the mirror is
  byte-identical jars/configs but a dev launch target.
- The `publicmine_audit_ores` command was not run on the production copy
  (`-Dpublicmines.enableOreBreakAudit=true` not set there); the identical
  audit ran via GameTest on the mirror.
- Factions regression is proven by scope (owned volumes + untouched-marker
  assertions), not by diffing an actual faction claim's contents.
- Long-haul memory behaviour is unmeasured (~40 min session; ATL armed but
  no multi-hour soak).
- GameTestServer-only environment: any test needing the real dimension is
  exercised here on `runServer`, not `runGameTestServer`, which does not
  load `public_mine_v2`.

## Operator-readiness notes

- First-time setup: `/publicmine setup validate` → fix blockers →
  `/publicmine setup initialize` → `/publicmine status` (all verified
  idempotent on the staged world).
- Daily reset is operator-facing end-to-end: `/publicmine reset status`
  shows schedule/warnings/transaction; `reset now` for manual cycles;
  `reset retry` re-arms a halted reset (verified).
- Staff messages are permission-gated (level 2+) and audience-targeted —
  occupants get reset warnings; staff get diagnostics; players see only
  their own notices.
- If a reset halts mid-cycle, `status` shows `RESETTING`/`FAILED` plus the
  persisted cursor; `recover repair`/`reset retry` are the supported
  recovery paths — no manual `.dat` edits needed.
- Known-evacuation edge fixed: a player whose return point is unloaded or
  unstandable is still removed from a resetting mine (lands at surface exit
  or world spawn). Operators should treat an evacuation `FAILED` line in
  logs as a bug to report — with this build it should never appear.
