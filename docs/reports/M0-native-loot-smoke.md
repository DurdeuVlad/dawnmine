# M0 native loot-table smoke evidence

## Run

This exact-pack server-console run evaluated the runtime loot table for every one of the 99 ore/resource candidates in [M0-ore-candidates.csv](M0-ore-candidates.csv). It used Minecraft 1.21.1, NeoForge 21.1.248, Java 21.0.12, all 283 Rustic Craft 2 2.2.2 pack JARs, plus `publicmines-0.1.0.jar` and `publicmines_auditprobe-0.1.0.jar`. The isolated server reached `Done (11.899s)!` and exited cleanly with code 0. The results table is [M0-native-loot-smoke.csv](M0-native-loot-smoke.csv).

For each candidate, the server ran `setblock 100 100 100 <block>` and then `loot spawn 100 100 100 mine 100 100 100 minecraft:diamond_pickaxe`, recording the console's `Dropped N [item] from loot table <id>` output and, where the spawned stack was observable, an item-entity data query. The test chunk had been force-loaded for the run.

Source log: `C:\m0s-audit\audit-run-9.log` (SHA-256 `2E16A9E05B26FCDC1ED72128A7127441D639A6E8F1AFD8281AE97144DBDBA440`, final flushed file). An immutable compressed copy is checked in as [M0-loot-smoke-run-9.log.gz](M0-loot-smoke-run-9.log.gz) (SHA-256 `1AAD58F39F1A32B7D1D1C4F8B723041DB6F90C6C68B884BC11F5CCD197DC6EDE`) and decompresses to exactly this file. An earlier recorded hash was taken before the shutdown output finished flushing and is superseded. The checked-in result CSV contains the normalized per-candidate outcome and server log timestamp; re-parsing the current source log reproduces the checked-in CSV byte-for-byte.

An earlier run on the original 29-candidate table used the same harness; its archived log is `C:\m0s-audit\logs\2026-11-02-7.log.gz` (gzip SHA-256 `0D7A7780FB3EE419F31DB569473CDCAD8134A4B42A907495D002FB18215A8720`, decompressed SHA-256 `2CDBBCFFEB337DB8938E38A60E239F93B539E0B8B2E1DEEA92D4FCDD3B91B4F8`). Its results for those 29 blocks are consistent with the expanded run: the same two candidates produced zero items both times.

## Results

- Runtime loot tables evaluated: 99 / 99 candidates.
- Tables producing item stacks with the diamond-pickaxe context: 67.
- Tables producing zero items: 32 — `born_in_chaos_v1:infected_diamond_ore`, `born_in_chaos_v1:infected_deepslate_diamond_ore`, `minecraft:sculk_vein`, and 29 `realmrpg_skeletons:*` prop blocks.
- The two Born in Chaos infected ore candidates produce no direct item under the tested runtime table/tool context. Their player-break behavior appears to make them encounter nodes instead; see below.
- The name-sweep additions evaluated as material-producing: `born_in_chaos_v1:dark_metal_deposit` dropped dark metal pieces, `jewelry:gem_vein`/`deepslate_gem_vein` dropped gem items, and the Butchery/Hearth-and-Harvest/Hexalia salt blocks dropped salt.
- The 29 zero-dropping `realmrpg_skeletons:*` props list material outputs in their static loot JSON but produced nothing in this `/loot ... mine` context — conditions such as tool requirements are unresolved; they are catalogued as loot-sweep candidates, not verified drops.

## Evidence limits

`/loot ... mine` evaluates the resolved server loot table with a supplied tool context. It is stronger than reading static JSON because it includes runtime datapack/loot resolution, but it does not perform a real player break and does not trigger custom player-break hooks, player-specific conditions, enchantments, or progression requirements. One trial was run per candidate. Randomized counts are observations from this run, not expected yields or balance estimates. `observed_item_id` is populated only where the spawned stack was queryable in the same tick; the `Dropped ... from loot table` console lines are authoritative for all rows.

## Born in Chaos infected-ore encounter connection

The two infected diamond ores returned zero items from `/loot ... mine`, but they are not ordinary ore blocks. In the pinned Born in Chaos JAR (`born_in_chaos_[Neoforge]_1.21.1_1.7.6.jar`, SHA-256 `A0271A24DB8622434D9573C5E789D451A05C9211173885049284E1911E2499C7`), both block classes override `onDestroyedByPlayer`: `InfectedDiamondOreBlock` calls `INSESTProcedure.execute`, while `InfectedDeepslateDiamondOreBlock` calls `InfectedDiamondOreBrekProcedure.execute`. The server paths in both procedures call the registered `DIAMOND_TERMITE` entity spawn. This is bytecode/resource evidence, not a player-break playtest.

The related `diamond_termite` entity loot table gives a `born_in_chaos_v1:diamond_termite_shard`; the pack recipe `data/born_in_chaos_v1/recipe/diamond_thermite_shard_k.json` consumes nine shards for one `minecraft:diamond`. In a second exact-pack runtime run, a summoned Diamond Termite was killed by the server command and produced one shard item entity. The run used Minecraft 1.21.1 / NeoForge 21.1.248, reached `Done (11.605s)!`, and its retained source log is `C:\m0s-audit\repro\M0-diamond-termite-kill.log`, SHA-256 `4EA5D798A14816B5A6FC655E471527727CCDF5966CEBF48FC7DDD8B6DBCA12C7`.

This gives a pack-grounded candidate loop: breaking an infected ore can trigger a Diamond Termite encounter; the mob can yield shards; nine shards have a diamond recipe. It may support the intended mining-to-PvE-to-equipment progression. The connected loop remains a candidate until a real player breaks the block, fights the termite, collects the shard, and crafts the diamond. The kill test used a server command with no player damage or Looting, and the recipe was checked in the pinned JAR rather than crafted in-game. Do not add a second custom reward to this behavior before playtesting the existing loop.

The entity class's bytecode sets base attributes of 14 health, 8 armor, 2 attack damage, 0.3 movement speed, and 14 follow range. These values do not establish encounter difficulty; player loadout, AI behavior, spawn placement, and team counterplay still need a playtest.
