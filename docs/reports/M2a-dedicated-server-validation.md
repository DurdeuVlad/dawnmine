# M2a dedicated-server topology smoke evidence

Date: 2026-11-02

## Environment

- Minecraft 1.21.1, NeoForge 21.1.248, Eclipse Temurin Java 21.0.12.
- Isolated Gradle `runServer` world under `runs/server`; the offline dev server was bound to `127.0.0.1` and stopped after verification. No Rustic Craft pack or Born in Chaos jar was loaded, which is outside M2a scope.
- `publicmines:public_mine` loaded from the mod's data resources as a real server dimension.

## Procedure and results

1. Started `gradlew.bat runServer --no-daemon --console=plain`; the server reached `Done` and loaded topology profile `standard`.
2. `/publicmine setup validate` passed with `publicmines:public_mine` loaded and no prior run.
3. `/publicmine setup initialize` created shared run `2026-11-02-1`, recorded bounds and the seed, planned 100 nodes / 104 segments / 3 checkpoints / 3 regions, then verified the M1 arrival pad and marked the run READY. Topology planning and validation occurred before arrival-pad world mutation; M2a placed no mine geometry.
4. `/publicmine setup validate` passed with the receipt: 100 nodes, 104 segments, 3 checkpoints, 3 regions, SHA-256 fingerprint `5f9ddad8b44e` (display prefix).
5. `/publicmine status` showed READY, profile `standard`, the same plan counts and fingerprint.
6. Stopped the server cleanly, restarted the same dev world, and observed the persisted READY run `2026-11-02-1` load with the same seed and two owned volumes. Post-restart validation reproduced fingerprint `5f9ddad8b44e` and passed; status remained READY.
7. Stopped the server cleanly after the restart check. RCON was disabled again and no listener remains on the test ports.

## Limits

This validates M2a's real custom-dimension initialization, saved receipt reload, deterministic replan, and admin reporting in a local dedicated dev server. It does not validate M2b block geometry/frontier expansion, a full Rustic Craft pack, player-client behavior, or multiplayer. Those belong to their respective milestones.
