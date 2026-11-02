# M0 static recipe and equipment candidate audit

## Scope and result

This is a static scan of recipe JSON resources from the pinned Rustic Craft 2 2.2.2 server mod JAR set, supplemented by the specific runtime durability values below. It identifies candidate material-processing and equipment recipes for product planning. It does not prove recipes load at runtime, other item statistics, mining drops, or combat effects. It is not a complete equipment balance review.

The scan checked 25,417 recipe resources across the 283 expected pack JARs and found 3,340 material/equipment candidate rows. 5 recipe resources failed JSON parsing and are excluded; they need explicit follow-up if they are relevant. The full candidate index is [M0-recipe-equipment-candidates.csv.gz](M0-recipe-equipment-candidates.csv.gz).

Candidate index SHA-256: `4FF305B29A7F37BABE5A1E0E7D8ABE64CA7D0791E56BCE1A5989DC3874CA33E4`.

The report generator verifies each expected pack JAR SHA-256 against [M0-server-mods.sha256](M0-server-mods.sha256), rejects missing or modified pack JARs, and rejects unexpected JARs other than the two documented audit additions.

## Pack-grounded progression hooks

- Steel: 2 indexed recipe row(s) produce `magistuarmory:steel_ingot`; 403 equipment candidate rows mention steel. The direct processing recipe ID is `magistuarmory:furnace/steel_ingot_blasting` in `epic-knights-1.21.1-neoforge-10.12.jar` (`data/magistuarmory/recipe/furnace/steel_ingot_blasting.json`): one `minecraft:iron_ingot` input becomes one `magistuarmory:steel_ingot` with 0.5 XP and 40-tick cook time. Steel weapon recipes can therefore support an iron-to-steel combat upgrade gate; this does not establish steel mining-tool availability or player unlock pacing.
  The steel shortsword recipe (`magistuarmory:steel_shortsword`) uses `#c:ingots/steel` and `#magistuarmory:hilts` in `epic-knights-1.21.1-neoforge-10.12.jar` (`data/magistuarmory/recipe/steel_shortsword.json`).
- Silver: 137 candidate recipe row(s) mention silver in a candidate weapon/armor recipe. The silver shortsword recipe (`magistuarmory:silver_shortsword`) uses `#c:ingots/silver` and `#magistuarmory:hilts` in `epic-knights-1.21.1-neoforge-10.12.jar` (`data/magistuarmory/recipe/silver_shortsword.json`). These are candidate hooks for a silver-specific combat gate; the pack recipe data does not prove which Born in Chaos mobs require silver damage.
- Ore-derived item outputs can be cross-referenced with the 29-row runtime candidate scan, but recipe tables alone do not prove that an ore block drops the listed item in gameplay.

## Runtime durability evidence

An exact-pack server startup loaded the pack's `overrides/common/kubejs/startup_scripts/steel_durability.js`. Its startup log confirms same-class Spartan greatswords were assigned distinct max durability values: silver 420, iron 720, steel 1,200, and diamond 2,400. These match the script's material ladder (silver 350, iron 600, steel 1,000, diamond 2,000) multiplied by the greatsword class factor (1.2). The same log records silver longsword 350, iron longsword 600, steel longsword 1,000, and diamond longsword 2,000, matching the 1.0 class factor.

| Material | Runtime item | Max durability | Startup log timestamp |
| --- | --- | ---: | --- |
| Silver | `spartan_weaponry_unofficial:silver_greatsword` | 420 | `2026-11-02 02:51:11.563` |
| Iron | `spartan_weaponry_unofficial:iron_greatsword` | 720 | `2026-11-02 02:51:11.789` |
| Steel | `spartan_weaponry_unofficial:steel_greatsword` | 1,200 | `2026-11-02 02:51:11.622` |
| Diamond | `spartan_weaponry_unofficial:diamond_greatsword` | 2,400 | `2026-11-02 02:51:11.684` |

This verifies a runtime **durability** progression for these named items. It does not establish relative attack damage, attack speed, armor, mining harvest level, or combat performance. The selected original log entries and startup summary are preserved in [M0-equipment-durability-log.txt](M0-equipment-durability-log.txt); the full source startup log is retained at `C:\m0s-audit\logs\latest.log` (SHA-256 `4EA5D798A14816B5A6FC655E471527727CCDF5966CEBF48FC7DDD8B6DBCA12C7`). The exact-pack startup reached `Done` as recorded in [M0-runtime-audit.md](M0-runtime-audit.md).

## Limits and follow-up

- The scan follows standard recipe JSON resource directories and common input key names. Custom serializers may encode inputs or results differently; this index should not be treated as exhaustive for code-defined recipes.
- Static recipe presence is not proof of active conditions, datapack override order, runtime recipe-book exposure, item attribute values beyond the specific durability log entries above, tool mining speed, or combat damage.
- Verify candidate recipe availability, native ore drops, item combat attributes, mining performance, and any material-specific effects in a disposable server before finalizing tier rewards.
- This report adds evidence to M0 but does not close M0 or replace the required gameplay and equipment review.

## Reproduction

```powershell
python tools\audit\summarize_recipe_equipment.py `
  --mods-dir C:\m0s-audit\mods `
  --expected-pack-hashes docs\reports\M0-server-mods.sha256 `
  --out-dir docs\reports
```

## Malformed resources

- `culturaldelights-0.17.8.jar` — `data/minecraft/recipe/beetroot_soup.json`: Expecting value: line 1 column 1 (char 0)
- `culturaldelights-0.17.8.jar` — `data/minecraft/recipe/mushroom_stew.json`: Expecting value: line 1 column 1 (char 0)
- `culturaldelights-0.17.8.jar` — `data/minecraft/recipe/rabbit_stew_from_brown_mushroom.json`: Expecting value: line 1 column 1 (char 0)
- `culturaldelights-0.17.8.jar` — `data/minecraft/recipe/rabbit_stew_from_red_mushroom.json`: Expecting value: line 1 column 1 (char 0)
- `simplymore-neoforge-1.3.0_alpha5+1.21.1.jar` — `data/simplymore/recipe/matterbane_clean.json`: Extra data: line 13 column 1 (char 199)
