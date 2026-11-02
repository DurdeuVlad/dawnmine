# M2c dedicated-server validation

## Environment

- Minecraft 1.21.1, NeoForge 21.1.248, Java 21.
- Local dedicated server in the M2c worktree.
- A fresh world smoke checked chunk-generation-time shell placement in the
  versioned `publicmines:public_mine_v2` dimension.
- The prior `publicmines:public_mine` experiment is intentionally not used or
  migrated; its chunks cannot be entered through the active v2 mine route.

## Automated checks

Command: `gradlew.bat clean test build`

Result: **BUILD SUCCESSFUL**. All 59 unit tests passed, including shell
face/corner bounds, floor/roof coverage, and the
existing topology, generation, ownership, and restart-cursor tests. Compilation
emitted one pre-existing deprecation warning for
`GameTestHelper.makeMockServerPlayerInLevel()`.

`git diff --check` passed before documentation was added; rerun before delivery.

## Dedicated-server observations

On a fresh world, `/publicmine setup initialize` completed with 99 topology
nodes and 103 edges at radius 64 in `publicmines:public_mine_v2`. Loaded
boundary probes at X/Z coordinates -1024 and 1023 (all four sides and one
corner) matched bedrock at Y=100. The center chunk had bedrock at Y=-64 and
Y=319; temporary probe blocks were restored to bedrock. The arrival pad and
generated route remained inside the enclosure.

Server logs recorded 6,368 changed blocks for each sampled boundary chunk in
0.91–1.35 ms during this run. This covers chunk generation only, not full-pack
startup or concurrent load. No `Can't keep up` warning appeared during the
sampled boundary probes. No legacy repair runs: the active dimension has a new
registry key, and the shell is generated before each new chunk becomes
available. Old experimental dimensions cannot be entered through the active
mine manager.

## Caveats and unverified behavior

- The dimension is configured with vanilla features and carvers disabled, so
  natural caves are prevented in this dimension instead of detected and
  replaced after generation. This was validated for the bundled dimension
  settings, not a complete Rustic Craft modpack launch. M7 must verify that no
  pack datapack or mod overrides those settings.
- Boundary generation was sampled, not exhaustively forced for every shell
  chunk. Worldgen is lazy; full 128-by-128 chunk
  coverage was not loaded.
- No graphical client was available, so survival-mode attempts to mine through
  the shell, exploration paths, and approved-entry traversal were not tested
  with a real player.
- Upgrading an already-used pre-v2 mine world is not supported. The old
  dimension remains outside the active mine route; operators must initialize
  a new run using v2 before opening entry.
- No cleanup/reset was run against the smoke world. The architecture requires
  the shell to persist across resets; reset integration must be verified in
  the daily-reset milestone.
- The full target-pack cave-intersection fixture and unchanged factions-world
  fixture remain for pack validation. The mine dimension is separately owned
  and the smoke did not modify the factions world.
