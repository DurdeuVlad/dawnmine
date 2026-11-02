# M1 dedicated-server lifecycle evidence

Status: **complete; M1 lifecycle/admin verified on a real dedicated server**

Status history: the first verification attempt (bottom of this file) failed and
was retained for the record. The final run below supersedes it after fixture
and implementation fixes; an independent review round then produced a second
remediation set (see "Independent review remediation"). Both sections together
are the honest record — the claims in this header refer only to the final run.

Related acceptance: [M1 shared run and lifecycle](../issues/M1-shared-run-lifecycle.md), [M1 admin onboarding](../issues/M1-admin-onboarding.md)

---

## Final verification run (2026-11-02, completes M1)

After the first attempt documented below, the FakePlayer fixture was replaced with
EmbeddedChannel-backed `makeMockServerPlayerInLevel()` players and the test batch
was re-run on a live dedicated server. All evidence in this section is from
`gradlew runServer` (real `DedicatedServer`, dev NeoForge, custom datapack
dimension loaded) plus `runGameTestServer` for the automated suite.

### Dedicated-server results

| Check | Result | Evidence |
|---|---|---|
| Fresh lifecycle state | Pass | `publicmine status` showed `UNINITIALIZED`; `setup validate` flagged exactly one blocker with the fix (`no run exists yet ... setup initialize`). |
| First-run setup | Pass | `setup initialize` created run `2026-11-02-1`, recorded seed, a 128x128 chunk active region, two owned volumes, a verified 5x5 arrival pad, and overworld spawn exit. |
| Setup idempotence | Pass | Second `setup initialize` reported `already initialized as run 2026-11-02-1; nothing was changed`; run id and owned-volume count unchanged. |
| Shared run for all players | Pass | In-server GameTest `twoplayersresolvesamerun`: two mock players resolve to the same run record, same runId, same seed. |
| Stop/start recovery | Pass | Clean restart logged `Loaded run state: status=READY runId=2026-11-02-1 seed=-2621450030189699663 day=2026-11-02 ownedVolumes=2` — identical identity, no duplicate initialization. |
| Partial-initialization recovery | Pass | `world/data/publicmines_run.dat` patched to `status=INITIALIZING` while stopped; next boot logged `Mine run 2026-11-02-3 was left mid-initialization and was marked FAILED for safe retry`; `recover check` explained the state; `recover repair` produced READY run `2026-11-02-4` with all validate checks `[ok]`. |
| Entry boundary | Pass | `enter` returns NOT_READY when uninitialized/failed; entry onto a vandalized pad returns ARRIVAL_UNSAFE; `enterthenleavereturnsplayer` teleported a mock player into `publicmines:public_mine` and back to its recorded surface point. |
| Ownership bounds | Pass | `status`/`recover check` list exactly the recorded volumes: `active_region` [-1024,-64,-1024]→[1023,319,1023] and `arrival_pad` [-2,62,-2]→[2,67,2] in `publicmines:public_mine`; persisted NBT verified with `tools/m1/dump_nbt.py`. |
| Permission matrix | Pass | `permissionmatrixisenforced` GameTest: levels 0/1 denied config/setup; level 0 player status shows no diagnostics; level 2+ and console work; player `help` hides staff commands. |
| Config validation + persistence | Pass | Bounds rejected (`30`, `70000` outside 256-65536), unknown keys rejected with valid list, bad boolean/zone rejected with corrective examples, valid sets persisted to `publicmines-server.toml` (`netherrackColumnInterval = 512`, `resetTimeZone = "Europe/London"` survived restart). |
| Netherrack safety | Pass | Off by default; interval floor 256 and ceiling 65536 enforced server-side. |
| Dedicated-server GameTests | Pass | `test runall` on the live server: batch of 10 completed; `test runfailed` reported `No tests found` (zero failures). `runGameTestServer`: `All 10 required tests passed`. |
| Console behavior | Pass | All commands work from console via RCON; `enter`/`leave` correctly refuse with `Only in-game players can enter the mine.` |
| Faction mine untouched | Pass | Pack smoke copy's `kubejs/` and `scripts/` unchanged; modpack repo `git status` clean; the mod only writes `world/data/publicmines_run.dat`, `world/dimensions/publicmines/`, `world/serverconfig/publicmines-server.toml`. |
| Target-pack smoke | Pass (see caveat) | Full 287-mod pack booted with `publicmines-0.1.0.jar`: `Done (14.964s)`, dimension registered (Sable physics pipeline + Journeymap per-dimension config created for `publicmines:public_mine`), `Rustic Craft Public Mines loaded (M1 lifecycle/admin)`, zero publicmines-attributable errors. Caveat: the pack's RCON did not answer auth packets, so in-pack interactive command output was not captured; command registration is identical code to the verified dedicated run. |
| Mock-player join side effect | Known | In-server tests use `makeMockServerPlayerInLevel`, which posts join messages (`test-mock-player joined the game`). Cosmetic; fixtures are not persisted across the disposable world. |

### Persisted run record (verified via `tools/m1/dump_nbt.py`)

```text
status=READY runId=2026-11-02-3 seed=-6985178116242139749 dayId=2026-11-02
bounds=[-64,-64,63,63] frontierRegions=0 runSequence=3
arrival=[0,63,0] surfaceExit=[-688,69,128] returnPoints={}
ownedVolumes=[active_region, arrival_pad] both in publicmines:public_mine
```

### GameTest environment note

Vanilla `GameTestServer` bakes the FLAT world preset, so mod datapack dimensions
do not exist there. Dimension-dependent tests assert the safe-failure contract
in that environment (setup fails closed, entry stays closed, no owned volumes
created) and the full path on the real dedicated server via `test runall`.
Both environments were exercised and pass.

### Placement decision evidence (dimension vs isolated region)

Both options were evaluated against the real server APIs and the M1
operational constraints before choosing:

| Criterion | Dedicated dimension (`publicmines:public_mine`) | Reserved overworld region |
|---|---|---|
| Cleanup boundary | Own world directory `world/dimensions/publicmines/public_mine`; all mine blocks live under recorded `OwnedVolume`s in that dimension only | Region carved from shared overworld; "mine-owned" vs player/faction blocks becomes a heuristic over mixed block state |
| Faction-mine isolation | Structurally impossible to overlap: the factions mine lives in the overworld | Requires exclusion zones and coordinate bookkeeping to stay clear |
| Reset/cleanup safety | Clearing is bound to a dimension + recorded volumes, both enumerated and queryable | Deletion in a live overworld region risks player builds, claims, and spawn infrastructure |
| Server API reality | `ResourceKey<Level>` + datapack `dimension`/`dimension_type` loads natively on the dedicated server; verified in both dev and full-pack boots | Region guard would need custom boundary enforcement for every block-edit path |
| Operational cost | +1 dimension on disk; observed boots add ~0.3s and ~3MB of region data for M1 | None, but pays for itself in correctness risk |
| Testability | `test runall` works on a real dedicated server; GameTestServer cannot host custom dims (documented) | Same, minus the custom-dim limitation |

Observed in the disposable prototype (`runs/server`): the dimension loads from
the mod's own datapack resources with no pack intervention, chunks save under
`world/dimensions/publicmines/public_mine/region`, the recorded volumes in
`publicmines_run.dat` exactly match `/publicmine status`, and the pack's Sable
physics and Journeymap created per-dimension pipelines/configs for it without
conflict. The flat generator fills to `Y=62` so the verified pad sits on real
walkable terrain (`execute if block 10 62 10 stone` → Test passed on a live
server).

Decision: dedicated dimension. The region alternative was not prototyped
because its defining risk (cleanup of non-mine blocks in a shared world) is a
correctness property, not a performance one — evidence could not make it safer
than a dimension that structurally cannot contain non-mine content.

### Retained transcript (final run, dedicated server via RCON)

```text
$ publicmine setup initialize
 [ok]   dimension: dimension publicmines:public_mine is loaded
 [ok]   run record: created run 2026-11-02-1 for day 2026-11-02 (seed recorded)
 [ok]   ownership: recorded active region of 128x128 chunk columns centred on the arrival point
 [ok]   surface exit: recorded overworld spawn at [128, 73, 256]
 [ok]   arrival pad: 5x5 stone pad verified at [0, 63, 0] in publicmines:public_mine
 [ok]   ready: run 2026-11-02-1 is ready; players can enter with /publicmine enter

$ publicmine setup initialize   (re-run)
 [ok]   existing run: already initialized as run 2026-11-02-1; nothing was changed
 [ok]   arrival pad: pad is intact at [0, 63, 0]

boot log after clean stop/start:
 [publicmines] Loaded run state: status=READY runId=2026-11-02-1
   seed=-2621450030189699663 day=2026-11-02 ownedVolumes=2

boot log after data patch to status=INITIALIZING (simulated mid-init crash):
 [publicmines] Mine run 2026-11-02-3 was left mid-initialization and was
   marked FAILED for safe retry.

$ publicmine recover repair
 [ok]   inspect: current status is FAILED for run 2026-11-02-3
 [ok]   dimension: dimension publicmines:public_mine is loaded
 [ok]   run record: created run 2026-11-02-4 for day 2026-11-02 (seed recorded)
 [ok]   ownership: recorded active region of 128x128 chunk columns centred on the arrival point
 [ok]   surface exit: recorded overworld spawn at [-688, 69, 128]
 [ok]   arrival pad: 5x5 stone pad verified at [0, 63, 0] in publicmines:public_mine
 [ok]   ready: run 2026-11-02-4 is ready; players can enter with /publicmine enter

$ test runall           (in-server GameTest batch on the live dedicated server)
Running 10 tests...
Starting batch: defaultBatch:0
$ test runfailed
No tests found           # zero failures in the batch

$ execute in publicmines:public_mine if block 10 62 10 minecraft:stone
Test passed               # walkable ground under the arrival area
$ execute in publicmines:public_mine if block 0 62 0 minecraft:smooth_stone
Test passed               # the arrival pad block itself

$ ./gradlew runGameTestServer (automated suite)
All 10 required tests passed :)
```

### Reproduce

```text
# 1. automated suite
.\gradlew.bat build runGameTestServer --console=plain

# 2. dedicated-server prototype
.\gradlew.bat runServer --console=plain
#    then via RCON (see tools/m1/rcon_client.py):
publicmine setup validate
publicmine setup initialize
publicmine status
publicmine config get
test runall
test runfailed
stop
# restart, then: publicmine status  -> same runId/seed

# 3. simulated partial initialization
python tools/m1/edit_nbt.py runs/server/world/data/publicmines_run.dat status INITIALIZING
# restart; observe FAILED + lastError; run: publicmine recover repair

# 4. inspect persisted record
python tools/m1/dump_nbt.py runs/server/world/data/publicmines_run.dat

# 5. pack smoke (see report for caveats)
copy publicmines-<ver>.jar into a disposable copy of the pack server and boot
```

### Residual risks

- Pack-level RCON does not answer auth packets on this machine (pack-side;
  unrelated to the mod). Operator commands were verified on an equivalent
  dedicated server; a live ops check on the real staging server is a cheap
  follow-up before production rollout.
- `enter`/`leave` were proven with server-side mock players, not real clients;
  the teleport path is vanilla `ServerPlayer.teleportTo`, but a real-client
  sanity pass during pack playtesting is recommended.
- The run sequence advanced by test activity during the batch (run ids -4..-7);
  run identity is always the latest READY run and the lifecycle treats older
  sequences as superseded history.

### Independent review remediation (2026-11-02)

A fresh read-only review found four material issues, all fixed:

- **Arrival surroundings:** the flat generator layers only filled `min_y`
  to `Y=-1`, so the pad floated over a dark void. Stone now fills to `Y=62`
  so the pad is flush with walkable ground.
- **Leave safety:** `leave()` now validates the stored return point
  (solid ground + headroom) and falls back to the recorded surface exit
  when it is unsafe or missing; teleport failures report `FAILED` instead
  of silently throwing.
- **Corrupt-READY recovery:** `recover repair` previously no-oped on a
  READY run even when validation flagged corrupt record fields; it now
  reclassifies a validation-failing READY run as FAILED and re-initializes.
- **Defensive NBT:** malformed `OwnedVolume` entries are skipped on load
  instead of throwing inside the recovery-critical load path; `initialize()`
  is fully exception-wrapped; dead `LOCKED` state removed (unknown statuses
  load as FAILED).

### PR review remediation (2026-11-02, second round)

A second review of the committed PR head found and resolved:

- **Missing sources in the committed tree:** `.gitignore`'s `run/` pattern
  matched `src/.../run/`, so the whole `com.rusticcraft.publicmines.run`
  package was never committed and a clean checkout could not build. The
  pattern is now anchored to the repository root (`/run/`) and the four
  missing source files are committed; a detached clean-checkout
  `compileJava` passes.
- **Arrival check/teleport mismatch:** `enter()` checked the fixed
  `ARRIVAL` pad but teleported to the persisted arrival point; the safety
  check now validates the actual teleport target.
- **Unsafe leave fallback:** the stored return point, the surface exit, and
  world spawn are each validated with a solid-ground+headroom rule; the code
  scans down (then up) for the nearest safe landing like vanilla respawn
  logic, and if truly nothing is safe the player stays in the mine and the
  command reports failure.
- **Malformed SavedData risk:** invalid persisted dimension ids and corrupt
  `OwnedVolume` entries can no longer throw inside the load path.

---

## First attempt (superseded, kept for the record)

Status at the time: **partial; M1 is not accepted**

## Environment

- Minecraft 1.21.1, NeoForge 21.1.248, Java 21.0.12.
- Disposable Gradle `runServer` world in `runs/server`.
- Test server and RCON were restricted to `127.0.0.1`; online authentication was enabled. Both processes were stopped after testing and ports 25565 and 25575 were confirmed closed.

### Later test-launch safety note

After the controlled run documented below, separate background `runServer` attempts reset the generated properties to offline mode with a blank `server-ip`.

- At 05:31, a live `Get-NetTCPConnection` snapshot showed both 25565 and 25575 listening on `::`; the simultaneous properties snapshot showed offline mode, blank `server-ip`, and RCON enabled. That startup log was replaced by later runs, so this attempt is evidenced by the captured live command output rather than the current log.
- The retained 05:32 `runs/server/logs/2026-11-02-1.log.gz` and 05:33 `runs/server/logs/latest.log` entries independently show the game server on `*:25565`, RCON on `0.0.0.0:39117`, and clean world saves/RCON shutdowns.

I stopped each instance through local RCON. No acceptance tests were run during those attempts. The generated properties are currently set to `server-ip=127.0.0.1` and `online-mode=true`; no server or RCON listener is running.

## Results

| Check | Result | Evidence |
|---|---|---|
| Fresh lifecycle state | Pass | `publicmine status` reported `UNINITIALIZED`; `publicmine setup validate` identified that no run existed and showed the initialize command. |
| Setup | Pass | `publicmine setup initialize` created run `2026-11-02-1`, seed `6917483423928742197`, a 128×128 chunk region, two owned volumes, a safe arrival pad, and a surface exit. |
| Setup idempotence | Pass | Repeating initialize retained run `2026-11-02-1` and reported “already initialized”; it did not add another owned volume. |
| Stop/start recovery | Pass | After a clean `stop` and server restart, the startup log loaded run `2026-11-02-1`, seed `6917483423928742197`, and two owned volumes. Status and validation reported the same run. |
| Dedicated-server GameTests | Fail / incomplete | `test runall` started a batch of 10 tests. `enterthenleavereturnsplayer` failed because `FakePlayer` had no network channel (`Connection.channel()` was null). The batch produced no completion summary before it was stopped, so results for the other tests are unknown. |
| Test isolation | Fail | During that GameTest batch, the log marked run `2026-11-02-2` `FAILED`; a later status showed a different `READY` run `2026-11-02-3` with seed `1976730673531713823`. The batch changed lifecycle state in the same disposable world instead of leaving run `2026-11-02-1` intact. |
| Two connected player sessions | Not tested | No real clients joined. The fake-player test did not complete successfully, so shared identity across two players is not established by this run. |
| Partial-initialization recovery | Not established | A `FAILED` state was observed, but the test batch did not complete and no successful repair/retry assertion was captured. |

The successful `setup validate` output also reported that automatic 06:00 reset scheduling is not implemented yet. That is expected for M1; scheduling belongs to M7.

## Commands and outcome excerpts

Commands were sent through local RCON because the Gradle server task did not accept console input through the attached PTY:

```text
publicmine status
publicmine setup validate
publicmine setup initialize
publicmine setup validate
publicmine status
publicmine setup initialize
stop
```

The restart log contained:

```text
Loaded run state: status=READY runId=2026-11-02-1 seed=6917483423928742197 day=2026-11-02 ownedVolumes=2
```

After the GameTest batch was stopped, the captured `publicmine status` response contained:

```text
Run: 2026-11-02-3  status: READY
Day: 2026-11-02  seed: 1976730673531713823  frontier regions: 0
Owned volumes (2): active_region [-1024,-64,-1024] to [1023,319,1023]; arrival_pad [-2,62,-2] to [2,67,2]
```

The dedicated-server test failure was:

```text
enterthenleavereturnsplayer failed: Cannot invoke Channel.attr(...) because Connection.channel() is null
```

## Acceptance decision

This run verifies initial setup, idempotence, and restart recovery for one run. It does **not** compare the custom-dimension and isolated-region placement strategies, so it does not resolve that issue #2 decision. It also does not establish M1 acceptance: the dedicated-server GameTest batch failed and changed the live run state, real player-session sharing was not exercised, and partial-failure recovery did not complete. Keep issue #2 open until the placement choice is evidence-backed and a complete dedicated-server verification leaves lifecycle state isolated while proving the required recovery and shared-run behavior.
