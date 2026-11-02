# M5b Foreman's Dispatch locker validation

## Result

- `./gradlew.bat build` passed on 2026-11-02 (includes `./gradlew.bat test`: 97 JUnit tests, 0 failures, 0 errors; `DispatchDataTest` 3/3). Only warnings: 3 pre-existing `makeMockServerPlayerInLevel()` deprecation notices.
- `./gradlew.bat runGameTestServer` completed 23 tests in 2.637 s. All 8 dispatch tests pass. 3 required tests fail identically to pre-M5b baselines and are unrelated to this issue: `minebreaktorchandsafezoneeventsareprotected` and `everycatalogoreusesnativeplayerbreakbehavior` are environment-dependent (mine dimension absent in the flat GameTest world / audit position entity conflict), and `malformedreadyreceiptblocksarrivalpadrepair` fails at the boundary-shell ordering gate before its topology assertion (pre-existing).
- Dedicated NeoForge 21.1.248 server (dev runtime, world `m5b-dispatch`, ports 25566/25666) boots clean with no client-only class loads and no dispatch errors.
- `git diff --check` passed.
- Adversarial review: a `flux-pr-review`-style pass was performed on the pushed diff by the implementing agent's review lane. Findings remediated in-commit: (a) no-space shift-click feedback was silent — `DispatchMenu.quickMoveStack` now reports "Your Foreman's Dispatch is full." / "Your inventory is full." to the acting player; (b) a player record missing `owner` could load a NIL-UUID locker — now fails closed like every other malformed record. Remaining nits (equivalent captured-player vs opener, bounded empty-locker records) are documented, not blocking. A fresh independent reviewer pass on the PR is still required before merge per repository rules; recorded here honestly when it completes.

## Dedicated-server observations (real `runServer`)

- `/publicmine help` lists `/publicmine dispatch` as a player command.
- `/publicmine dispatch` from the console (RCON) answers "Only in-game players can open Foreman's Dispatch."
- `test runall` on the live server ran all 23 GameTests against the real `public_mine_v2` dimension. `dispatchAtSurfaceDepotServesSameLockerAndSurvivesDisconnect` passed end-to-end: a mock player was denied the menu 64+ blocks off-pad, opened the locker via `/publicmine dispatch` at the fabricated surface depot, shift-click-deposited 10 diamonds into the persistent container, reopened across a `closeContainer`, teleported into `public_mine_v2` onto a registered checkpoint pad, and reopened the *same* locker contents through the real checkpoint terminal. The real `connection.disconnect` path closed the menu and freed the session lock (log: `test-mock-player lost connection: dispatch depot test complete`).
- `logoutReleasesSessionAndReturnsCarriedStack` passed: cursor-carried stack returned to the logging-out player's inventory and the session lock released.
- Run-state churn: `publicmine setup initialize` ran (fails at the ore-catalog completeness gate because 13 modpack ores are absent in the dev runtime) and `dispatchRestartVerify` then confirmed the seeded sentinel locker was untouched — run lifecycle operations do not disturb `publicmines_dispatch` data.
- Restart persistence pair: `test run dispatchrestartseed` planted `7 x netherite_scrap` at slot 20 under a fixed sentinel UUID; `save-all` + `stop` wrote `runs/server/m5b-dispatch/data/publicmines_dispatch.dat` (NBT inspected: `schema=1`, owner record with `id=minecraft:netherite_scrap`, `count=7`, `slot=20`); after a full stop/start the fresh-boot `test run dispatchrestartverify` logged `Dispatch restart verify observed slot 20 = 7 minecraft:netherite_scrap` — byte-exact reload from disk.

## Implemented contract

- `DispatchData` (`publicmines_dispatch` SavedData on the overworld storage) is player-owned durable data keyed by stable UUID, independent of `MineRunData`. `beginNewRun`/reseed has no reference to it; daily reset therefore cannot erase lockers. Schema version 1, ≤10,000 lockers, registry-aware `ItemStack.save/parse` per slot.
- Malformed or future-schema saves fail closed: the whole record is preserved verbatim in `unsupportedData`, `isAvailable()` reports false, and the command tells the player to contact an administrator. Nothing is silently deleted.
- `DispatchContainer` is the single authoritative 54-slot view; `DispatchMenu` is the vanilla `GENERIC_9x6` chest menu (exactly 54 slots). `stillValid` re-checks server player, same UUID, and station authorization.
- `DispatchManager` authorizes only inside the recorded surface-exit safe zone (overworld) or a registered checkpoint station safe volume (mine dimension), and only while the run is `READY` — the M5a 5×5×4 pads; there are no arbitrary-coordinate terminals. The per-UUID session lock prevents one player opening two concurrent Dispatch menus; different players' sessions are independent.
- Transactional movement: shift-click into a full locker leaves the source stack unchanged; vanilla `closeContainer`/disconnect paths are reconciled — `PlayerLoggedOutEvent` returns the carried stack via `placeItemBackInInventory` and releases the lock, and the per-tick session reconciliation drops locks whose menu vanished without `removed` (vanilla never calls `removed` on disconnect).
- `/publicmine dispatch` is a player-only command with guidance feedback for unavailable data, wrong location, and already-open states.

## Test coverage map (issue AC 6)

- UUID ownership/isolation: `uuidLockersHaveSeparateContentsAndFixedBounds`, `DispatchDataTest.rejectsDuplicateConcurrentSessionAndReleasesForReconnect`.
- Persistence migration: `itemStacksPersistAcrossSavedDataReload` (schema-less v0 record loads unchanged), `malformedAndFutureSchemaFailClosedWithoutChangingSavedBytes`, `outOfBoundsSlotFailsClosedAndPreservesSavedData`.
- Full inventory: `fullLockerTransferLeavesPlayerStackUntouched`.
- Reconnect/logout: `logoutReleasesSessionAndReturnsCarriedStack`, `retainSessionsDropsOrphanedLocksAndKeepsOpenOnes`, depot test disconnect leg.
- Simultaneous access: different-owner sessions acquire independently; same-owner second session refused ("Your Dispatch is already open.").
- Restart/reseed: `dispatchRestartSeed`/`dispatchRestartVerify` pair plus dedicated-server run-churn observation.

## Residual verification limits

- No two real connected clients: isolation and simultaneous access are proven by the per-UUID container/session design plus mock `ServerPlayer` tests on the live server, not by two physical clients. The `mct` CLI is not installed.
- The bare dev server cannot reach `RunStatus.READY` (ore-catalog completeness gate needs the full Rustic Craft 2 pack), so a naturally generated checkpoint was not produced there; the depot GameTest registers a real `OwnedVolume` checkpoint station in the real `public_mine_v2` dimension via the same code path generation uses, and the snapshot/restore fixture leaves run state untouched. A full-pack (staged `_rc220_composed`) end-to-end run remains unperformed.
- M7's actual daily-reset trigger does not exist yet (issue #10 in flight); reset-persistence is proven by `beginNewRun`/`setup initialize` churn + restart, not by the real daily scheduler.
- `test runfailed` re-runs rather than lists — noted because it replayed `dispatchrestartverify` mid-sequence once; the final saved-state inspection and boot-C verify are the authoritative persistence evidence.
- One `runGameTestServer` invocation stalled inside the `m4a_encounters` batch on the persisted `runs/gameTestServer/world` (pre-existing test, untouched by this PR; no dispatch test was running). Moving the stale world aside and re-running on a fresh disposable world reproduced the clean baseline above.
- Accepted product risk (post-review disclosure): `DispatchData.load` fails closed on any unreadable item, so a pack update that *removes* a modded item present in any locker makes `/publicmine dispatch` unavailable server-wide until an operator repairs `publicmines_dispatch.dat` offline (delete the offending `players` entry; all other lockers then load). Original bytes are preserved untouched on disk. Stacks above an item's normal max (command/mod-granted) are stored and round-tripped like vanilla chests rather than rejected, so they can never produce an unloadable save.
