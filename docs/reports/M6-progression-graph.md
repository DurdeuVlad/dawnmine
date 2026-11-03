# M6 progression graph evidence

Issue: [#13](https://github.com/DurdeuVlad/dawnmine/issues/13) — "Durable equipment and depth progression economy".

This report backs the machine-readable graph in
`src/main/resources/data/publicmines/progression/standard.json`
(schema 1, SHA-256 fingerprint emitted on every load) and the flat CSV
mirror at `docs/reports/M6-progression-graph.csv`. Every pack claim is
grounded in the M0 audit artifacts of the exact composed pack
(`staged dir _rc220_composed`,
284 JARs, per-mod SHA-256 in `docs/reports/M0-server-mods.sha256`) or in
direct reads of the pinned JAR recipe/loot resources named per node.

## Verified pack facts used

| Claim | Evidence |
| --- | --- |
| `minecraft:iron_ingot` → `magistuarmory:steel_ingot` via `magistuarmory:furnace/steel_ingot_blasting` (blast 40 ticks, 0.5 xp) | `M0-recipe-equipment-audit.md`; `epic-knights-1.21.1-neoforge-10.12.jar` recipe |
| **Runtime recipe-id rule:** the composed pack's `overgeared*` integration jars overlay/disable the vanilla-style recipe ids (`magistuarmory:*`, `minecraft:iron_pickaxe`, `overgeared:steel_pickaxe` are absent from the live `RecipeManager`). The graph therefore declares the *runtime* ids, all confirmed by a `RecipeManager.byKey` + producer-sweep on the staged 2.2.2 server | `pm-m6-audit` boot log, `MineProgressionRuntimeAudit` producer sweep |
| `minecraft:iron_pickaxe` via `overgearedspartan:iron/weapons/pickaxe` = `spartan_weaponry_unofficial:handle` + `overgeared:iron_pickaxe_head`; the `minecraft:iron_pickaxe` recipe id itself is disabled by a `neoforge:false` overlay in `overgearedspartan-0.68-neo.jar` | `overgearedspartan-0.68-neo.jar` recipe + overlay |
| `overgeared:steel_pickaxe` via `overgearedspartan:steel/weapons/pickaxe` = handle + `overgeared:steel_pickaxe_head`; `overgeared:steel_pickaxe` recipe id disabled the same way | `overgearedspartan-0.68-neo.jar` recipe + overlay |
| `magistuarmory:steel_shortsword` via `overgeared_epic_knights:crafting/shortsword/steel_shortsword` = `overgeared_epic_knights:steel_shortsword_blade` + `magistuarmory:hilt` (blade is forged from steel on the overgeared anvil) | `overgeared_epic_knights-1.21.1-1.1.0.jar` recipe |
| `magistuarmory:steel_kiteshield` via `overgeared_epic_knights:forging/kiteshield/steel_kiteshield` = `overgeared:steel_plate` ×2 around `magistuarmory:wood_kiteshield` (`overgeared:forging` minigame) | `overgeared_epic_knights-1.21.1-1.1.0.jar` recipe |
| `magistuarmory:gold_kiteshield` via `overgeared_epic_knights:forging/kiteshield/gold_kiteshield` = `overgeared_epic_knights:gold_plate` ×2 + `wood_kiteshield` | `overgeared_epic_knights-1.21.1-1.1.0.jar` recipe |
| `magistuarmory:diamond_kiteshield` via `overgeared_epic_knights:smithing/steel_kiteshield_to_diamond_kiteshield` = `steel_kiteshield` + `diamond` + `overgeared:diamond_upgrade_smithing_template` (smithing_transform — **does** consume the template; the direct `magistuarmory:diamond_kiteshield` recipe is shadowed) | `overgeared_epic_knights-1.21.1-1.1.0.jar` recipe |
| `werewolves:raw_silver` → `werewolves:silver_ingot` via `werewolves:silver_ingot_from_blasting_raw_silver`; `magistuarmory:silver_shortsword` via `overgeared_epic_knights:crafting/shortsword/silver_shortsword` (`silver_shortsword_blade` + hilt) + `werewolves:silver_sword` consume `#c:ingots/silver` | `Werewolves-1.21-2.0.3.3.jar` recipes + `data/c/tags/item/ingots/silver.json`; `overgeared_epic_knights` silver shortsword recipe |
| Steel→diamond upgrades consume `overgeared:diamond_upgrade_smithing_template` (`steel_pickaxe_to_diamond_pickaxe`, `overgeared_epic_knights:smithing/steel_shortsword_to_diamond_shortsword`, `overgeared:steel_sword_to_diamond_sword`) | `M0-recipe-equipment-candidates.csv.gz` smithing_transform rows |
| Template duplication: 1 template + 6 diamond + 1 steel ingot → 2 templates (`overgeared:diamond_upgrade_smithing_template`); first copy only via overworld structure chests (`overgeared/loot_modifiers/diamond_upgrade_from_*.json`, 12 hooks @ chance 0.15) | `overgeared-1.21.1-1.6.17.jar` resources |
| Netherite uses the pack's forging chain, not vanilla crafting: `overgeared:heated_netherite_alloy` (4 scrap + 4 gold ingot) then `data/minecraft/recipe/netherite_ingot.json` (`overgeared:forging`, master minigame, failure refunds 4 scrap) | `overgeared-1.21.1-1.6.17.jar` recipes |
| Infected diamond ores summon `born_in_chaos_v1:diamond_termite` on player destruction; shard loot table; `born_in_chaos_v1:diamond_thermite_shard_k` = 9 shards → 1 diamond | `M0-native-loot-smoke.md/.csv`; `M4a-encounter-validation.md`; `born_in_chaos_[Neoforge]_1.21.1_1.7.6.jar` resources |
| `born_in_chaos_v1:dark_metal_deposit` drops 4–7 `pieceofdarkmetal`; nugget/ingot recipes `dark_metal_nugget_craft_4`/`_2`; armor plate chain needs `monster_skin` + `dark_upgrade` | `born_in_chaos` block loot table + `M0-recipe-equipment-candidates.csv.gz` |
| Runtime durability ladder (longsword class): silver ~350–420 / iron 600 / steel 1000 / diamond 2000–2400 | `M0-equipment-durability-log.txt` |
| Silver weakness unproven for configured mine mobs | `M0-recipe-equipment-audit.md` (recorded as `candidate`, not claimed) |

## Source categories across the graph

- **Mining** — every configured `kind: resource` ore-catalog entry that
  yields a registered drop appears as a `block:` source; completeness
  asserts the catalog's `native_drop_items` contain the material (or one
  of its `coDrops`, used for the eight-gem pool).
- **PvE** — the three configured mine reward tiers
  (`reward:upper|middle|chaos` → raw iron @20%, raw silver @10%,
  diamond @2%) plus the encounter-composition pressure that makes
  combat-capable routes viable. No `entity:` source is currently
  reachable in the configured mine — see blocked nodes.
- **Exploration** — overworld structure chests for the
  `diamond_upgrade_smithing_template` (`outsideMine: true`) and vanilla
  bastion chests for the netherite template (recorded with an explicit
  pack-overlay uncertainty). This is intentional: the deepest tool gate
  asks for between-run overworld preparation, exactly matching the
  "different problem per depth" design rule.

## Yield bounds (per run, shared)

Placement bounds are computed from the topology profile
(`data/publicmines/topology_profiles/standard.json`: 5 / 7 / 7 resource
fields for upper / middle / chaos, 2 veins per field) times each ore
entry's `max_vein_size` from the ore catalog. A material's *maximum* ore
blocks per run = fields × veins × vein cap (assuming every field rolled
that ore — the honest upper bound); resource-weight shares are quoted
for context (upper 33, middle 21, chaos 13 resource weight).

| Material | Max ore blocks/run | Bounded combat/route addition |
| --- | --- | --- |
| coal / copper / iron | 30 | iron: +20% × ≤8 tracked upper kills ≈ 1.6 expected |
| zinc / sulfur / truffle | 20 | — |
| silver / gold / nether gold / lapis / redstone / seabrass | 28 | silver: +10% × ≤12 tracked middle kills ≈ 1.2 expected |
| gems (either band) | 14 | one gem per vein from the verified 8-gem pool |
| diamond | 28 | +2% × ≤16 tracked chaos kills ≈ 0.32 expected |
| emerald / debris / dark metal deposit | 14 | deposit drops 4–7 pieces (verified table) |
| quartz | 28 | — |

Combat rewards are already hard-bounded by `MineRunData` claim-once
semantics, per-region population caps (8/12/16), and the reward
catalog's own 0–16 count validation; the graph repeats those bounds for
yield math but adds no new grant mechanism.

## Upgrade contract check

Each of the 9 upgrades states an earlier problem it reduces and a
*different* later problem it does not solve (asserted non-blank and
unequal by `ProgressionGraphTest`):

- `iron_tool_gate` — unlocks iron-tier harvest; does not touch
  debris/infected tiers or combat pressure.
- `steel_sidearm`, `steel_shield_wall`, `steel_mining_kit` — steel
  durability/defense/harvest endurance for middle; none opens the chaos
  tool gate or answers focus-fire problems.
- `silver_option` — `candidate`: stone-tool-accessible weapon route;
  unproven counter to any configured mob.
- `diamond_pickaxe_depth_key` — the verified depth key (ancient debris
  becomes harvestable); the termite ambush, imp surges, and hazards
  remain. Infected ores are *not* tool-gated (any break summons the
  termite — recorded as evidence, not a gate).
- `diamond_shield_early`, `diamond_sidearm` — chaos-tier defense/damage
  without replacing coordination; both consume the scarce overworld
  template (the shield also consumes a full steel kiteshield).
- `netherite_armor_prep` — `candidate`: forging minigame + bastion
  template make it expensive and partial.

## Blocked nodes (verified gaps, not fabricated)

1. `born_in_chaos_v1:diamond_termite_shard` — the summon procedure,
   shard loot, and 9→1 diamond recipe are all verified artifacts, but
   the ore planner only rolls resource-kind entries and the entity is
   not a scheduled encounter, so infected ores are never placed. The
   combat-funded diamond route is unreachable today; M8 owns the
   placement decision.
2. `born_in_chaos_v1:monster_skin` — dark-metal armor plate input only
   from `nightmare_stalker`/`dread_hound`/`dire_hound_leader`, none of
   which is a configured mine encounter.
3. `born_in_chaos_v1:dark_upgrade` — only from `fallen_chaos_knight`
   (not configured) or BiC structure chests; deepest armor tier stays
   blocked upstream.

## Discovery

Players discover upgrades through `/publicmine progression` (per-tier
upgrade hints), the ore catalog's readable harvest gates, pack-native
recipe book/JEI (no custom items were added), and the bank-or-push
choice that M5a checkpoints already surface. See `discovery` in the
graph file for the recorded notes.
