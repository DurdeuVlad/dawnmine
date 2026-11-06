# Dawnmine 0.3 — Validation Report (DRAFT, NOT READY)

**Status: NOT READY.** The hub now generates on the dedicated staging server,
but behavioral verification and a failure-recovery follow-up are still in
progress under `docs/Dawnmine-0.3-adventure-contract.md`. Earlier legacy 0.2
reports (M0-M8, 39/39 GameTests) are historical evidence, not proof of 0.3.
No public release or production deployment is authorized.

## Implemented and unit-verified

- Dawnmine rename end-to-end (package `com.dwurdy.dawnmine`, mod id `dawnmine`, `dawnmine:mine` dimension, `/dawnmine`, `dawnmine_*` saved data, `dawnmine-server.toml`, artifact `dawnmine-0.3.0.jar`, no `/publicmine` alias).
- Whole-run preparation plan (structural -> hub -> dressing -> ores) with persisted queue, bounded async chunk window, owned-ticket tracking and terminal-path release, and a fail-closed `preparation()` gate covering entry, travel, login recovery, status, and validate.
- Generated 45x45 underground hub (floor Y41, spawn 0,42,8, roof <=59) as a required prep segment; east gateway to entrance (28,42,0); hub reserve excluded from all carving, dressing, and ores; full-hub safezone.
- Lodestone teleporter: `dawnmine_teleporter` SavedData, `/dawnmine teleporter bind|status|unbind`, mainhand right-click consoles with exact dimension/position/block checks, success-only 40-tick cooldown cleared on logout/stop, hazard-free landing validation, atomic surface-exit safezone binding, unbind restores the spawn fallback without world mutation.
- `PassageNetwork` resolves close crossings and diagonal body sweeps into
  supported junctions and one-step ramps. The gateway/entrance remain fixed;
  excessive lowering and non-convergence fail closed.
- Baseline full build: `gradlew.bat build --console=plain` succeeds, with
  147 tests across 22 suites, zero failures/errors/skips. The original XML
  outputs are frozen in `build/verification/full-suite-e17a/`; these cover the
  baseline jar below, not subsequent unverified changes.
- Strict merged-geometry traversal asserts every main-route/checkpoint target
  reachable for seeds 42, 1337 and 2026. It rejects diagonal corner-cutting and
  invalid support. The previous 2/1/4 missing-target fixtures are resolved,
  not printed or accepted as successes.
- Teleporter GameTests (`TeleporterGameTests`, registered on `RegisterGameTestsEvent`): console identification, offhand barrel cancellation without action, unsafe/hazard landing rejection, bind->refuse->unbind->rebind lifecycle, physical hub-return path, prepared enter->leave round trip (requires prepared fixture).
- Migration tool: preflight all source/destination pairs, write and verify a
  temporary copy, fsync, then publish atomically with a non-replacing hard link.
  It never overwrites a destination or publishes a partial copy. Eight Python
  unit tests pass, including interrupted writes/retry and a destination created
  after preflight.
- Dispatch compatibility: historical 0.2.0 source at commit `515a43d` differs
  only in package and SavedData name; schema 1 and the complete load/save
  implementation are unchanged. This is source compatibility evidence, not a
  claim that arbitrary removed pack items can load.

## Dedicated-Server Baseline

The disposable exact-pack staging server boots the new underground-hub jar.
`boot-console-hub.log` records complete preparation of 1183 segments in
186123 ms, after admission remained closed during preparation. Subsequent
restart checks preserve the prepared run rather than carving it again.
The world is `dawnmine03hub`; the older test world is retained.

The inspected baseline jar is
`e17a165181eb79177591f751bafa1e723e65320897e48a6580b45244873ed38c`.
It is an intermediate local candidate, not a published release.

The production NeoForge launcher disables GameTests regardless of the JVM
enable flag: `GameTestHooks.isGametestEnabled()` requires
`!FMLLoader.isProduction()`. Behavioral tests therefore use the available
`runServer` development mirror with the same pack and a prepared-world
snapshot. A missing `/test` command on the production launcher is not counted
as a test pass or as proof that the development mirror is unavailable.

## Review

Independent source review approves the hub/generation/travel implementation
after fixing interrupted-copy safety, documenting the frozen historical audit
probe, centralizing menu closure and rejecting half-loaded bindings. Runtime
gates are explicitly not waived by that approval. A subsequent failed-run
recovery finding is under correction and needs its own regression/review.

## Explicit limitations

- No client visual playtest exists; in-game appearance is unverified.
- Merged-world traversal is a unit-level model (last-write-wins), not production `applyOperation`; runtime reachability proof is pending.
- Physical round-trip, private hub locker, binding/restart and current
  protection/reset/native-break GameTest outputs are pending.
- A catalog containing 32 ores is not proof that actual reachable walls expose
  those ores; generated-world observations remain necessary.
- The baseline unit count must not be used to cover later recovery changes.
