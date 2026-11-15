# M12d: the loot/POI system

## What shipped

- `LootSites.java` (new, `cave` package): mirrors `EncounterSites.java`'s
  deterministic planning pattern exactly - a pure function of (stack, seed)
  producing a list of planned container positions, sparser than encounters
  (`CELL=44`, `CELL_CHANCE=0.55` vs. encounters' `CELL=28`/`0.75`), away from
  the hub, never in a protected column.
- Containers are placed **at generation time**, not lazily on player
  approach. `/dawnmine census` already scans the whole pre-generated shell
  for real block entities (`RandomizableContainerBlockEntity` with an
  unresolved loot table - see `CensusService.holdsLoot`), and this mod
  already does whole-run pregeneration before entry opens, so baking loot
  into `CaveTerrain.carve()` alongside ore and landmarks (rather than
  mirroring the encounter system's lazy "wake near player" spawn) is both
  simpler and the only way the existing census tooling would ever see it.
- Containers use vanilla's own lazy loot-table mechanic: a chest or barrel
  is placed with a `LootTable` + seed attached to its block entity
  (`RandomizableContainerBlockEntity.setLootTable`), the same mechanic
  vanilla structure chests use. The table only resolves into real items the
  first time a player opens it - generation itself never constructs an
  `ItemStack`.
- Three region-tiered loot tables (`data/dawnmine/loot_table/chests/
  upper_crate.json`, `middle_crate.json`, `chaos_crate.json`), mine-native
  items only (raw ores, ingots, coal, redstone, lapis, quartz, diamond,
  emerald, deepslate, torches, rails, TNT), per the already-approved loot
  economy boundary - no exotic or pack-specific treasure items.
- Two container kinds per the issue's own minimum scope (`crate` = chest,
  `locker` = barrel), alternating per site; corpses/vaults/keys are an
  explicit fast-follow, not attempted here.

## A real bug found by the new regression test

The first version keyed the per-column lookup used during generation
(`LootSites.atColumn`) on `(x, z)` alone. Different layers plan their loot
independently and have nothing stopping them from landing on the same
`(x, z)` at different depths - when that happened, the second layer's site
silently overwrote the first's in the lookup map, and **the first layer's
container was never placed at all**, with no error or warning anywhere.
Caught by a dedicated regression test
(`LootSitesTest.columnsSharedAcrossLayersDoNotOverwriteEachOther`) before
this ever reached a live server. Fixed by keying the lookup on
`(layer, x, z)` and having `CaveTerrain.carve()` check every layer at each
column instead of assuming one site per column.

## Non-goals (explicitly not attempted here)

- No vaults, keys, dead-miner corpses, or the full five-tier rarity system
  from `Mine-Concept-v2.md` section 5 - the issue's own acceptance bar
  ("a minimal, honest first version... is the acceptance bar") is met with
  2 container kinds across 3 region tiers; the richer tiers are a
  documented fast-follow.
- No new persistence/claim-tracking code: because containers are placed at
  generation time (not lazily), none of the schema-versioned claim-set
  machinery `MineEncounterManager`/`MineRunData` use for mobs was needed.

## Verification

`./gradlew test` (full suite): BUILD SUCCESSFUL. New `LootSitesTest`
(deterministic planning, floor/hub-clearance invariants, the cross-layer
regression case) plus `EncounterSitesTest`'s own density-assertion loop
extended to the new layer count. Live dedicated-server boot and
`/dawnmine census` verification (confirming `loot` > 0 and real chest
contents) is recorded in the final M12 batch report.
