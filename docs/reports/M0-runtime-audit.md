# M0 runtime compatibility audit

## Scope and result

This report records compatibility evidence for the Rustic Craft 2 2.2.2 server pack. It is not a gameplay test and does not close M0: the ore candidates still need completeness and mining-drop review, and recipe/equipment evidence remains a static candidate scan except for the specific pack-startup durability values now recorded in [M0-recipe-equipment-audit.md](M0-recipe-equipment-audit.md). Runtime recipes, other item stats, and combat behavior remain unverified.

The isolated server used Minecraft 1.21.1, NeoForge 21.1.248, Java 21.0.12, all 283 server mod JARs named by the 2.2.2 pack manifest, the built `publicmines-0.1.0.jar`, and a temporary audit probe. The expected pack JAR set was complete with no missing entries. The only additional JARs were Public Mines and the audit probe. Per-JAR SHA-256 values are in [M0-server-mods.sha256](M0-server-mods.sha256). The tested Public Mines JAR SHA-256 was `D7A7E33F8074EDB993CE391BA6D5CCC1168C577AFAB48A36D398226ADA92103C`; the checked-in probe source originally built to SHA-256 `A920E739106CA451AA2ADAF3305E096CB2DD03DB5B72EF445C34B1FF23D3A858`, and after the `item-tags` subcommand was added it builds to `7BC1A503815CB3C3B26D15CFF8E7A94FBFBAF1E3C8606B2FEFD61471D6CBDCFF`.

The harness reached `Done (11.197s)!`, logged both the Public Mines server entry point and the checked-in audit probe, ran `/publicmines-audit block-tags`, and wrote 77,854 tab-delimited output rows. The harness returned exit code 0. During shutdown, however, AllTheLeaks warned that a thread appeared stuck. The pack-only baseline produced the same warning and also returned exit code 0, so the warning was not introduced by Public Mines or the probe. Two independent re-runs (`audit-run-6.log`, `audit-run-7.log`) reproduced the same startup, identical block-tag output, and clean exit code 0; run 7 also executed `/publicmines-audit item-tags`, writing 78,491 rows.

A control run used the same isolated server and pack files with only the Public Mines and audit probe JARs removed. Both the full run and baseline reached `Done`; both logged the same RuntimeDistCleaner dedicated-server client-class errors and the same AllTheLeaks stuck-thread warning. Comparing normalized `/ERROR]` log entries produced 465 entries in each run with no differences. This attributes those logged errors to the existing pack/runtime baseline for this test, without claiming the baseline is error-free. Selected exact log lines and run details are in [M0-server-smoke-evidence.txt](M0-server-smoke-evidence.txt).

## Runtime block/tag snapshot

The resolved server registry contained 23,256 block IDs, 1,399 unique block tag IDs, and 76,725 non-empty block/tag memberships. Of those blocks, 22,127 had at least one tag and 1,129 had none. The complete compressed snapshot is [M0-runtime-block-tags.tsv.gz](M0-runtime-block-tags.tsv.gz); its SHA-256 is `4E6E46EE670CCC8841AE311474480F6027B6721E82B5C6AD8B8101ED76939281`. The uncompressed tab-delimited data SHA-256 is `1AD376814FB485DF6864B5A62A8E0FF48E499218FA6B9F1A4BAC987659EB335F`.

The equivalent item-registry dump from run 7 contains 78,491 rows (item ID plus resolved tag memberships). The compressed snapshot is [M0-runtime-item-tags.tsv.gz](M0-runtime-item-tags.tsv.gz); its SHA-256 is `4260F997C08A2E102D25EB2C624FE687A835D87458C2E10F33AB7D42901E430F`. The uncompressed tab-delimited data SHA-256 is `6D1A89EF2AA82266E6707BE3350B0CF4AFA3F8A363C639543E92391A969E3311`. The recipe/equipment index uses this dump to classify candidates by resolved tags rather than names alone; see [M0-recipe-equipment-audit.md](M0-recipe-equipment-audit.md).

The resolved `c:ores` family contains 23 blocks: 21 members of `c:ores` itself plus the two Werewolves silver blocks under `c:ores/silver`. `minecraft:ancient_debris` is a common-tagged special case whose ID does not end in `_ore`. `fungidelight:truffle_dirt` is separately tagged by `fungidelight:truffle_ore` and is not in the common `c:ores` family.

The candidate table combines four independent discovery passes, each recorded per-row in `candidate_basis`: resolved `c:ores`-family members (23), resolved mod-specific `*_ore`/`*_ores` tags (1, `fungidelight:truffle_dirt`), every registered block ID ending in `_ore` (5 untagged), an ore-like name sweep over all 23,256 registered blocks (`deposit`/`vein`/`formation`/`salt`-token names, kept only when the block has material or unevaluated loot drops — 16 rows), and a loot-output sweep of every block loot table in all 283 pack JARs (material-producing blocks with no ore signals — 54 rows). The resulting table has 99 rows in [M0-ore-candidates.csv](M0-ore-candidates.csv).

Five named ore candidates lack a resolved `c:ores` family tag and require explicit catalog decisions:

- `abyssal_decor:seabrass_ore`
- `born_in_chaos_v1:infected_deepslate_diamond_ore`
- `born_in_chaos_v1:infected_diamond_ore`
- `butchery:deepslate_sulfur_ore`
- `butchery:sulfur_ore`

The name sweep found additional ore-equivalent resource blocks the tag/`_ore` passes missed, notably `born_in_chaos_v1:dark_metal_deposit` (drops `pieceofdarkmetal`, iron-tier pickaxe gating) and the Jewelry `gem_vein`/`deepslate_gem_vein` pair (six gem outputs), plus a salt family across Butchery, Hearth and Harvest, and Hexalia. The loot-output sweep catalogs material-dropping blocks with no ore signals — dominated by `realmrpg_skeletons:*` skeleton-prop blocks and decor lamps — each flagged `assessment` to classify as ore tier versus salvage/loot node rather than silently claimed as ore or silently excluded.

This is still called a candidate scan, not a completeness proof: the inventory covers every block whose resource nature is visible to resolved runtime tags, block naming, ore-like naming, or static loot-table outputs, but a block that yields ore only through code hooks with none of those signals would remain invisible to this audit. Runtime `/loot ... mine` evaluation for every catalogued row is in [M0-native-loot-smoke.md](M0-native-loot-smoke.md); real player-break behavior (hook procedures such as the infected-ore Diamond Termite path) stays an explicit M3/M4 gate.

## Born in Chaos registry evidence

The pinned artifact is `born_in_chaos_[Neoforge]_1.21.1_1.7.6.jar`, SHA-256 `A0271A24DB8622434D9573C5E789D451A05C9211173885049284E1911E2499C7`. `javap -v -p` inspection of `net.mcreator.borninchaosv.init.BornInChaosV1ModEntities` found 98 entity registrations and mapped every registration to its constructor class and `MobCategory`.

The registry categories are 80 `MONSTER`, 4 living `CREATURE`/`WATER_CREATURE`, and 14 `MISC`. `MONSTER` is a spawn-category clue, not proof that an entity is hostile, safe to summon, or appropriate for a mine encounter. Every entity remains a candidate until its AI, behavior, spawn requirements, and multiplayer counterplay are reviewed. The exact registry table is [M0-bic-entities.csv](M0-bic-entities.csv).

## Reproduction

1. Build the probe using the command in [the probe README](../../tools/audit/runtime-tag-probe/README.md).
2. In a disposable NeoForge 21.1.248 server, stage the exact pack files listed in `M0-server-mods.sha256`, the Public Mines jar, and the probe jar. Do not use the live server directory.
3. After startup reaches `Done`, run `publicmines-audit block-tags` and `publicmines-audit item-tags` from the console and then `stop`. The outputs are `reports/runtime-block-tags.tsv` and `reports/runtime-item-tags.tsv` in that server directory.
4. Recreate the BiC disassembly with JDK 21:

   ```powershell
   javap -classpath "<path-to-born_in_chaos_[Neoforge]_1.21.1_1.7.6.jar>" -v -p net.mcreator.borninchaosv.init.BornInChaosV1ModEntities
   ```

5. The pack mod hash list was produced from the authoritative 2.2.2 server manifest and compared against the staged `mods` directory. It contained 283 expected pack JARs, no missing files, and only the two documented audit additions.
6. Regenerate the ore table, BiC table, compressed runtime snapshot, and verified pack hash list:

   ```powershell
   python tools\audit\summarize_m0.py `
     --mods-dir "<isolated-server>\mods" `
     --runtime-tags "<isolated-server>\reports\runtime-block-tags.tsv" `
     --bic-javap "<path-to-bic-entities.javap.txt>" `
     --expected-pack-hashes docs\reports\M0-server-mods.sha256 `
     --allowed-extra-jar publicmines-0.1.0.jar `
     --allowed-extra-jar publicmines_auditprobe-0.1.0.jar `
     --out-dir docs\reports
   ```

## Remaining evidence and open items

- The candidate reconciliation is now bounded to the four recorded passes (common tags, mod ore tags, ore-like names, all-JAR loot outputs) plus runtime `/loot ... mine` evaluation of every catalogued row. The residual gap is a block that yields ore only through code hooks with none of those signals; eliminating that possibility requires player-break testing of the world, which is an M3 gate, not a static-audit claim.
- Real player-break hooks remain unverified: the infected-ore Diamond Termite path is bytecode evidence, not a playtest (M4). See [M0-native-loot-smoke.md](M0-native-loot-smoke.md), including the infected-ore → Diamond Termite → shard → diamond recipe candidate loop.
- The 5 malformed recipe resources and custom/code-defined recipes remain unverified; relevant recipes need runtime verification and item attribute/mining/combat inspection before candidates become progression equipment (M6).
- Independent review of the extraction method and candidate classifications is required before M0 is marked complete; the issue stays open until the ore-audit completeness bound is accepted.
