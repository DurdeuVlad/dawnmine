# M0 native loot-table smoke evidence

## Run

This exact-pack server-console run evaluated the runtime loot table for every one of the 29 current ore candidates. It used Minecraft 1.21.1, NeoForge 21.1.248, Java 21.0.12, all 283 Rustic Craft 2 2.2.2 pack JARs, plus `publicmines-0.1.0.jar` and `publicmines_auditprobe-0.1.0.jar`. The isolated server reached `Done (11.048s)!`. The results table is [M0-native-loot-smoke.csv](M0-native-loot-smoke.csv).

For each candidate, the server ran `setblock 100 100 100 <block>` and then `loot spawn 100 100 100 mine 100 100 100 minecraft:diamond_pickaxe`, followed by an item-entity data query. The test chunk had been force-loaded for the run; the test block and force-load were removed before shutdown.

Archived source log: `C:\\m0s-audit\\logs\\2026-11-02-7.log.gz` (gzip SHA-256 `0D7A7780FB3EE419F31DB569473CDCAD8134A4B42A907495D002FB18215A8720`)

Decompressed source log SHA-256: `2CDBBCFFEB337DB8938E38A60E239F93B539E0B8B2E1DEEA92D4FCDD3B91B4F8`. The checked-in result CSV contains the normalized per-candidate output and server log timestamp.

## Results

- Runtime loot tables evaluated: 29 / 29 candidates.
- Tables producing item stacks with the diamond-pickaxe context: 27.
- Tables producing zero items: 2 — `born_in_chaos_v1:infected_diamond_ore` and `born_in_chaos_v1:infected_deepslate_diamond_ore`.
- These two Born in Chaos infected ore candidates produce no direct item under the tested runtime table/tool context. Their player-break behavior appears to make them encounter nodes instead; see below.

## Evidence limits

`/loot ... mine` evaluates the resolved server loot table with a supplied tool context. It is stronger than reading static JSON because it includes runtime datapack/loot resolution, but it does not perform a real player break and does not trigger custom player-break hooks, player-specific conditions, enchantments, or progression requirements. One trial was run per candidate. Randomized counts are observations from this run, not expected yields or balance estimates.

## Born in Chaos infected-ore encounter connection

The two infected diamond ores returned zero items from `/loot ... mine`, but they are not ordinary ore blocks. In the pinned Born in Chaos JAR (`born_in_chaos_[Neoforge]_1.21.1_1.7.6.jar`, SHA-256 `A0271A24DB8622434D9573C5E789D451A05C9211173885049284E1911E2499C7`), both block classes override `onDestroyedByPlayer`: `InfectedDiamondOreBlock` calls `INSESTProcedure.execute`, while `InfectedDeepslateDiamondOreBlock` calls `InfectedDiamondOreBrekProcedure.execute`. The server paths in both procedures call the registered `DIAMOND_TERMITE` entity spawn. This is bytecode/resource evidence, not a player-break playtest.

The related `diamond_termite` entity loot table gives a `born_in_chaos_v1:diamond_termite_shard`; the pack recipe `data/born_in_chaos_v1/recipe/diamond_thermite_shard_k.json` consumes nine shards for one `minecraft:diamond`. In a second exact-pack runtime run, a summoned Diamond Termite was killed by the server command and produced one shard item entity. The run used Minecraft 1.21.1 / NeoForge 21.1.248, reached `Done (11.605s)!`, and its retained source log is `C:\m0s-audit\repro\M0-diamond-termite-kill.log`, SHA-256 `4EA5D798A14816B5A6FC655E471527727CCDF5966CEBF48FC7DDD8B6DBCA12C7`.

This gives a pack-grounded candidate loop: breaking an infected ore can trigger a Diamond Termite encounter; the mob can yield shards; nine shards have a diamond recipe. It may support the intended mining-to-PvE-to-equipment progression. The connected loop remains a candidate until a real player breaks the block, fights the termite, collects the shard, and crafts the diamond. The kill test used a server command with no player damage or Looting, and the recipe was checked in the pinned JAR rather than crafted in-game. Do not add a second custom reward to this behavior before playtesting the existing loop.

The entity class's bytecode sets base attributes of 14 health, 8 armor, 2 attack damage, 0.3 movement speed, and 14 follow range. These values do not establish encounter difficulty; player loadout, AI behavior, spawn placement, and team counterplay still need a playtest.
