# M4a encounter validation

## Automated checks

- `./gradlew.bat test build` — passed on the current M4a source; Gradle reported `BUILD SUCCESSFUL` (34 tasks, 3 executed, 31 up to date).
- `git diff --check` — passed before the final documentation update.
- The focused development dedicated-server GameTest `mineentitiesaretrackedandsafezonehostilesarerejected` passed earlier. It covers scheduled-origin tracking across movement/save/reload, reset cleanup, duplicate site claims, untagged hostiles retaining vanilla despawn behavior, and hostile rejection in a safe zone.
- The complete GameTest-server batch is not a passing signal for this branch: the harness does not load the custom `public_mine_v2` dimension and reported unrelated failing protection/arrival/catalog tests. Exact-pack runtime audit below supplies the BiC-specific evidence.

## Exact Rustic Craft pack audit

The audit used the pinned Rustic Craft 2 pack: Minecraft 1.21.1, NeoForge 21.1.248, Java 21.0.12, 283 pack mods, and Born in Chaos 1.7.6 (`born_in_chaos_[Neoforge]_1.21.1_1.7.6.jar`, SHA-256 `A0271A24DB8622434D9573C5E789D451A05C9211173885049284E1911E2499C7`). Public Mines was built from PR #31 head `2a1d3e3` and packaged as `publicmines-0.1.0.jar`, SHA-256 `417C6E0D3D2E30472DAEF33D4DE79ED698ECBC77D82F993B50B8BCC8620F050F`; that exact JAR is retained with the final audit artifacts.

The server reached `Done (10.133s)!`; its exact-pack runtime command `/publicmine_audit_encounters` returned `passed=true`, `cases=8`, and no failures. The eight cases were:

1. An unknown runtime entity ID caused its composition to fail closed without a substitute.
2. `born_in_chaos_v1:infected_diamond_ore` invoked through its registered `onDestroyedByPlayer` callback spawned a Diamond Termite with current-run origin data and reset-cleaned both the entity and record.
3. `born_in_chaos_v1:infected_deepslate_diamond_ore` passed the same callback, tracking, and reset-cleanup assertions.
4. A supported generated upper-room node spawned its scheduled, tracked encounter.
5. Replaying that generated room did not duplicate the encounter.
6. An unsupported surface produced no encounter and did not claim the site.
7. An upper-region population of eight rejected another scheduled spawn.
8. A global population of 28 spanning all three regions rejected another encounter while its local Chaos-region population was still below that region's own cap.

The scheduled table's profile tests assert distinct region compositions/counterplay and each composition stays within its configured cap. Startup in this same pinned pack resolved all configured entity IDs.

The audit runs in temporary world `publicmines-m4a-hook-callback-audit`, through an opt-in operator command enabled only with JVM property `publicmines.enableMineEncounterAudit=true`. The command also refuses to run in any differently named world. It snapshots/restores test blocks and only cleans entities belonging to its temporary run. Its complete console and stderr logs and the exact tested JAR are retained outside this repository at `C:\m0s-audit\reports\publicmines-m4a-hook-callback-audit\`. After the test, the previous exact-pack JAR and `server.properties` were restored and their hashes matched the preserved originals.

## Reward and behavior limits

The exact-pack M0 loot smoke independently observed a summoned Diamond Termite drop a `born_in_chaos_v1:diamond_termite_shard`; the pinned pack recipe converts nine shards to one diamond. The combination establishes the native infected-ore callback, mine-origin tracking/reset behavior, and the native shard loot table. The exact-pack audit uses a synthetic `ServerPlayer` to invoke the block callback, not a connected client mining and fighting in-game. A broader live player combat, collection, solo/group counterplay, and balance playtest remains a tester milestone; the server-side audit must not be described as that end-to-end playtest.

Scheduled encounter compositions are resolved against the exact runtime entity registry on startup. The configured table is limited to artifact-verified `born_in_chaos_v1:decrepit_skeleton` and `born_in_chaos_v1:bone_imp`; native Diamond Termites are captured only when the infected-ore procedure creates them. BiC behavior outside the mine is not changed.

## Framework references

The server-side tracking lifecycle follows the pinned NeoForge 1.21.1 [`EntityJoinLevelEvent`](https://lexxie.dev/neoforge/1.21.1/net/neoforged/neoforge/event/entity/EntityJoinLevelEvent.html) and [`EntityLeaveLevelEvent`](https://lexxie.dev/neoforge/1.21.1/net/neoforged/neoforge/event/entity/EntityLeaveLevelEvent.html) contracts. Entity-leave cleanup preserves records when entities are merely unloaded from chunks and removes them for actual death/discard.
