# M5a checkpoint travel validation

## Result

- `./gradlew.bat test build` passed on 2026-11-02 (34 Gradle tasks).
- `git diff --check` passed.
- Independent adversarial review: pass with caveats. The reviewer checked the final diff after the Overworld hot-path fix; no tests or server were run by the reviewer.
- Exact-pack multiplayer travel, simultaneous entry/leave, restart, and reset verification remain unperformed. The `mct` test CLI is not installed, and no connected client harness was used in this pass.

## Implemented contract

- Checkpoint availability is shared, keyed by stable generated topology node IDs, persisted with the active run, bounded to 256 stations, and cleared with run reset. This matches the accepted product decision in `Decision.md`.
- Checkpoints register after their generated node segment completes. Startup backfills stations for already-completed checkpoint nodes so pre-M5a runs can expose them after recovery/restart.
- `/publicmine checkpoints` lists the active run's stations; `/publicmine checkpoint <id>` travels only to a generated station. It rejects unavailable run states, does not force-load station chunks, falls back to the loaded entrance when needed, and removes a loaded station whose pad is invalid.
- Feedback shows depth/region, the next main-route step when known, and bank/extract versus push guidance. `/publicmine leave` continues to return the player to their saved surface location or safe fallback.
- The mine arrival pad and generated checkpoint pads use explicit 5×5×4 run-scoped safe-zone volumes. The one overworld zone is restricted to the exact recorded shared surface-exit pad; generic registration cannot target arbitrary external coordinates or other dimensions.
- M2c combat/effect protection and M4a hostile mob checks consume these explicit zones. Mine-wide PvP remains limited to the mine-owned area. Overworld membership uses direct lookup for the one recorded exit zone; other dimensions return early.

## Residual verification limits

The current verification proves persistence, bounds, idempotent registration, shared access records, reset expiry, external-zone dimension qualification, and build/test behavior. It does not prove a real player teleport, another connected player using a checkpoint they did not discover, simultaneous players, combat at live zone boundaries, restart recovery in the target pack, or daily-reset ejection. M5b's surface Dispatch interaction should use the registered surface-exit box or define a separately bounded policy before adding another external station.
