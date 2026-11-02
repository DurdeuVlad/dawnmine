# M2b dedicated-server validation

Date: 2026-11-02

## Environment

- Minecraft 1.21.1, NeoForge 21.1.248, Eclipse Temurin Java 21.0.12.
- Two isolated `gradlew.bat runServer` dev worlds under `runs/server`, bound
  to loopback with temporary RCON. Both were stopped cleanly, RCON was
  disabled again, and the original `server.properties` was restored.
- The Rustic Craft modpack and Born in Chaos were not loaded.

## Automated checks

- `gradlew.bat clean test build`: passed. All 59 unit tests passed (2 frontier,
  9 geometry, 11 generation-state and 37 topology/profile/validator/snapshot
  tests), and the mod jar built successfully. One existing deprecation warning remains in
  `MineLifecycleGameTests.makeMockServerPlayerInLevel()`.
- MC Pilot's `mct` CLI is not installed in this environment, so no client was
  available for real-player navigation or frontier-trigger verification.

## Dedicated-server procedure and results

1. Started a fresh dev server and confirmed `publicmines:public_mine` loaded.
2. Ran `/publicmine setup initialize`; it created a READY run with a validated
   topology, recorded ownership and arrival pad, and queued the entrance
   connector. This run planned 95 nodes / 99 edges / 3 checkpoints / 3 regions.
3. Kept the mine's adjacent chunk loaded with a temporary `forceload` command
   so the connector and entrance room could complete without a player ticket.
   The generator reported 2 completed segments and no pending/active work.
4. Connector profile: 1,130 operations, 500 real block writes, maximum 8
   operations in a tick, 74.27 ms total, 55.19 ms maximum slice, and 55.17 ms
   maximum single block operation. Node `m000` profile: 311 operations, 84
   writes, maximum 8 operations in a tick, 2.77 ms total, 0.15 ms maximum
   slice, and 0.10 ms maximum operation. The one expensive connector update
   exceeded the soft time budget; the per-operation cap bounded the remaining
   work, and the later node profile stayed below 1 ms per slice.
5. Ran `gradlew.bat clean test build` successfully after the profiling changes.
6. Removed the temporary force-load, stopped the server cleanly, restored
   `server.properties`, deleted the temporary RCON secret, and confirmed no
   listener remained on ports 25565 or 25576.

## Limits

This verifies real custom-dimension startup, topology validation, connector
and entrance-room block placement, server save/stop, and operation profiling.
The runtime smoke did not have a connected player and therefore did not prove
frontier growth after player movement, concurrent players, PvP/PvE rules,
liquid flow, checkpoint use or modpack integration. Those behaviors require
later milestone implementation and a client/modpack playtest.
