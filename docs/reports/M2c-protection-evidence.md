# M2c Protection Evidence

## Scope

This report records verification for issue #23, “M2c: Protect mine interactions and disable PvP.” The implementation limits survival breaking in active mine volumes to catalogued ores with exact generated-position provenance, permits only floor ordinary/soul torches, blocks common terrain-altering mechanisms, disables PvP in the active mine, and enforces registered safe-zone combat boundaries. Protection is scoped to Public Mines run-owned volumes and does not globally change other dimensions.

## Checks completed

| Check | Evidence | Result |
| --- | --- | --- |
| Unit suite and build | `./gradlew test build` | Passed; 34 actionable Gradle tasks. |
| Whitespace validation | `git diff --check` | Passed. |
| Focused server GameTest | `./gradlew runServer`, then `test run minebreaktorchandsafezoneeventsareprotected` | Passed. Covers non-ore denial, catalog ore without provenance denial, generated ore allow, floor torch allow/wall torch denial, PvP denial, safe-zone attacks across both directions, source-less harmful effects, hostile damage in/outside safe zone, projectile serialization/owner removal, projectile hit and post-block-impact splash denial, and run-data restoration. |
| Exact modpack startup | Dedicated server with the final M2c JAR and pinned NeoForge 21.1.248 / 283-mod pack | Passed startup; catalog loaded 32 entries with fingerprint `15661c507ce0`; Public Mines lifecycle reached ready. The exact production server disables GameTests, so behavior tests ran on the matching NeoForge development server. |

## Test fixture safety

The focused GameTest snapshots the entire shared `MineRunData` before installing its synthetic run and safe zone, backs up all world blocks it changes, and restores these in a `finally` block. It asserts serialized run data equals the pre-test snapshot after cleanup.

The exact-pack smoke test used an isolated temporary world and alternate server/RCON ports. It stopped the server and restored the baseline mod JAR and `server.properties`; their verified baseline SHA-256 hashes were `080FDFE831C8CF546E26B4909C972735C6713602B8A2EF216F1BE6CB716CCBEB` and `9D0E6C4D9A03BB083E83CB83D9EDA2A377801F78DD10B882FE9A66581EF00AE5` respectively.

## Residual verification limits

The reviewer confirmed the implementation and focused test pass, with caveats: explosion, piston, fluid, dispenser-equivalent, and mob-grief prevention event paths do not yet have dedicated GameTests. The focused test does not exercise every pack ore family or a live multi-player factions-mine control. Exact-pack production startup validates loading and compatibility, but its production mode disallows `/test`; it is not evidence that these untested event paths were behaviorally exercised. These should remain explicit follow-up checks before treating the full M2c acceptance matrix as runtime-verified.
