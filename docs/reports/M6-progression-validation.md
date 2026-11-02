# M6 progression validation

Issue: [#13](https://github.com/DurdeuVlad/rusticcraft-public-mines/issues/13) — "Durable equipment and depth progression economy".

## Automated checks

- `./gradlew.bat test` — BUILD SUCCESSFUL; **109 tests PASSED, 0 FAILED**, including all 9 `ProgressionGraphTest` cases (`bundledGraphParsesWithMaterialsUpgradesAndBlockedNodes`, `everyMaterialHasABoundedSourceASinkAYieldBoundAndEvidence`, `miningPveAndExplorationEachContributeToTheGraph`, `everyUpgradeNamesTheProblemItSolvesAndTheDifferentProblemItDoesNot`, `rewardSourcesResolveToConfiguredTiersThatYieldTheMaterial`, `miningSourcesResolveToOreCatalogBlocksWhoseDropsContainTheMaterial`, `missingItemsAreFlaggedNotDropped`, `blockedNodesRecordVerifiedGapsWithoutFabricatingSources`, `malformedGraphsFailClosed`).
- `./gradlew.bat jar` — BUILD SUCCESSFUL; `publicmines-0.1.0.jar`, SHA-256 `410072d03633fe10b4471029fba08d284c839529a8fccb0ea33d0932f850f39c` (byte-identical copy deployed to the audit server).
- `./gradlew.bat runGameTestServer` — the batch is **not** a passing signal for this branch (same caveat as `M4a-encounter-validation.md`): the flat-preset harness does not load `public_mine_v2`. Final grid was `[X++X+++++++++++X+++ ]` = 3 failed-required / 16 passed / 1 never-started before the runner was terminated. All three failures are pre-existing environment issues, unchanged by M6:
  - `minebreaktorchandsafezoneeventsareprotected` — "requires the public_mine_v2 dimension from the mod data pack" (documented in `M2c-protection-evidence.md` as a `runServer`-driven test).
  - `malformedreadyreceiptblocksarrivalpadrepair` — arrival/topology gate ordering, unrelated to M6 files.
  - `everycatalogoreusesnativeplayerbreakbehavior` — "audit position must be empty and clear of entities" placement flake in the flat world.
- Focused GameTest evidence on a development dedicated server (`./gradlew.bat runServer`, `Enabled Gametest Namespaces: [publicmines]`): each of the 5 `MineProgressionGameTests` was executed via `test run` and re-verified via `test verify` (**100 executions each, 500 total; zero `LogTestReporter` failures** — the default reporter logs every failure loudly, and none appeared):
  - `bankedmaterialssurvivenewrunreset`
  - `rewardsdonotduplicateacrossretryorreset`
  - `upgradechangeswhataplayercanharvest`
  - `progressiongraphloadsanddegradesgracefully`
  - `orecatalogstillcoverseveryprogressionminingsource`
- In the dev environment the graph correctly degrades: `Loaded progression graph: 20 materials, 9 upgrades, 3 blocked (fingerprint 414795f107ed, unavailable items: 14)` — pack items absent in dev are flagged, not dropped; cross-catalog integrity checks still run.

## Exact Rustic Craft pack audit

The audit used the pinned Rustic Craft 2 2.2.2 server pack staged at
`C:\Users\Vlad\Documents\Github\Rustic Craft Main Server Testing\pm-m6-audit`
(Minecraft 1.21.1, NeoForge 21.1.248, Java 21.0.12, 283 manifest JARs +
`publicmines-0.1.0.jar` above). JVM flags: `-Xmx10G -Xms4G
-Dpublicmines.enableMineProgressionAudit=true`. World
`publicmines-m6-progression-audit` (the audit refuses any other world).
Boot logs: `boot-m6-5.log` (prepare) and `boot-m6-6.log` (verify), kept with
the staged server.

Startup: `Done (8.681s)!`; `[publicmines] Loaded progression graph:
20 materials, 9 upgrades, 3 blocked (fingerprint 414795f107ed,
unavailable items: 0)` — every declared material/output/blocked item id
resolves in the real pack.

### Phase 1 — `publicmine_audit_progression prepare` → **passed, 7 cases, 0 failures**

- Mine init idempotent on existing run `2026-11-02-1`; arrival pad intact at `[0, 63, 0]`.
- Banked `12 minecraft:raw_iron + 3 minecraft:diamond` in an overworld chest at `[24, 80, 24]` for the persistence check.
- Graph resolution: `materials=20, upgrades=9, blocked=3, fingerprint=414795f107ed, integrity=true, recipeFailures=0` — **every recipe sink and upgrade recipe resolves in the live `RecipeManager` and produces the declared output**.
- Tool gates: `steel->debris=false`, `diamond->debris=true`, `iron->diamondOre=true` — the verified chaos depth key behaves as catalogued.
- Infected ores (`infected_diamond_ore`, `infected_deepslate_diamond_ore`): `steel-correct=true, diamond-correct=true` — **not a harvest gate**; the pack summons the diamond termite on any player break. Recorded as evidence; the depth-gate claim lives only on `ancient_debris`.
- Bounded yields logged: upper ~1.60 `raw_iron`, middle ~1.20 `raw_silver`, chaos ~0.32 `diamond` expected per run (reward chance × alive-tracked caps 8/12/16).

### Phase 2 — real server restart, then `publicmine_audit_progression verify` → **passed, 4 cases, 0 failures**

- `bank chest intact: 12 minecraft:raw_iron and 3 minecraft:diamond` — the banked store survived a full JVM restart.
- `bank chest survived a simulated daily reset into run 2026-11-02-m6-audit-e2f7d4d9` — a `beginNewRun` reset issued a new run id/seed and cleared run-scoped entities/checkpoints without touching banked materials.
- `reward claims: duplicate refused, stale-run entity refused after reset` — `MineRunData.claimMineReward` stays idempotent and run-scoped.
- Graph + tool gates re-verified post-restart: `recipeFailures=0`, `integrity=true`.

### Recipe-id resolution correction (runtime-verified)

An earlier graph draft declared vanilla-style recipe ids
(`magistuarmory:*`, `minecraft:iron_pickaxe`, `overgeared:steel_pickaxe`);
the first audit run showed they are **absent from the live
`RecipeManager`** in the composed pack — `overgearedspartan` ships
`neoforge:false` overlays for the pickaxe ids, and
`overgeared_epic_knights` integration recipes replace the
`magistuarmory:` crafting/smithing ids. The audit's producer sweep
(`RecipeManager.getRecipes()` filtered by declared output) recovered the
real ids, now declared in the graph and re-verified to `recipeFailures=0`:

| Output | Runtime recipe id | Type / inputs |
| --- | --- | --- |
| `minecraft:iron_pickaxe` | `overgearedspartan:iron/weapons/pickaxe` | `spartan_weaponry_unofficial:handle` + `overgeared:iron_pickaxe_head` |
| `overgeared:steel_pickaxe` | `overgearedspartan:steel/weapons/pickaxe` | handle + `overgeared:steel_pickaxe_head` |
| `magistuarmory:steel_shortsword` | `overgeared_epic_knights:crafting/shortsword/steel_shortsword` | `steel_shortsword_blade` + `hilt` |
| `magistuarmory:silver_shortsword` | `overgeared_epic_knights:crafting/shortsword/silver_shortsword` | `silver_shortsword_blade` + `hilt` |
| `magistuarmory:steel_kiteshield` | `overgeared_epic_knights:forging/kiteshield/steel_kiteshield` | `overgeared:forging`, 2× `steel_plate` on `wood_kiteshield` |
| `magistuarmory:gold_kiteshield` | `overgeared_epic_knights:forging/kiteshield/gold_kiteshield` | forging, 2× `gold_plate` on `wood_kiteshield` |
| `magistuarmory:diamond_kiteshield` | `overgeared_epic_knights:smithing/steel_kiteshield_to_diamond_kiteshield` | smithing_transform: `steel_kiteshield` + `diamond` + `overgeared:diamond_upgrade_smithing_template` |

Consequence honestly folded back into the graph: `diamond_shield_early`
is **not** template-free — it consumes the scarce overworld template and
a full steel kiteshield; its `solves`/`doesNotSolve` text and the CSV
mirror were updated to say so.

## Limits of this evidence

- The audit is **synthetic**, driven through an opt-in operator command —
  it is not a live player playtest. It proves registry/recipe/tool-gate
  resolution, banked-material persistence across a real restart and a
  simulated reset, and reward de-dup. It does not prove moment-to-moment
  combat, collection feel, or group counterplay.
- Infected diamond ores are **not harvest-gated** (any break summons the
  termite); they are also never placed by the resource-kind ore planner.
  The termite-shard diamond route is a documented **blocked node** owned
  by M8, not a reachable progression source.
- The deepest content's coordinated-group claim is **not yet proven in
  multiplayer**; per the milestone split that proof belongs to M8.
- `netherite_upgrade_smithing_template` sourcing is recorded as a
  vanilla-bastion assumption with an explicit pack-overlay uncertainty.
- The GameTest-server batch shows 3 pre-existing environment failures
  (documented above and in `M4a-encounter-validation.md`); focused
  per-test verification supplies the positive signal instead.
- Unrelated pack noise on the staged server (TaCZ data-loader failures,
  skipped recipes from other mods) was observed but does not touch M6
  code paths.
