# Dawnmine Mine v2 - Concept (APPROVED by owner 2026-11-11)

Status: approved concept with the owner decisions in section 10 folded in. Implementation proceeds by staged PRs (section 9).

## 1. Why v1 failed (diagnosis)

v1 was built as a **graph first, place second**: nodes and edges (rooms joined by tunnels), then ore and mobs sprinkled on top as afterthoughts. We spent M10 polishing the shape of walls and fixing trapped cells, and never measured the things that make a mine fun. Result:

| Symptom (owner playtest) | Root cause |
|---|---|
| No mobs | Mobs were a spawn *rule*, not a designed encounter. Nothing is placed, so nothing is met. |
| Too little ore | Ore was a budget applied after carving. No rule says "a miner always sees ore". |
| No loot | Loot did not exist as a system at all. |
| Not fun to explore | Passages were uniform. Nothing differs between the 5th and 50th room, and nothing pulls you around a corner. |

**Lesson:** content density and variety must be the design input, and geometry serves it. Every future claim is measured by a census, not judged by eye.

## 2. Design pillars

1. **Something every 20 blocks.** Walking any route, a player sees an ore vein, a mob group, a loot spot, a landmark or a hazard at least every ~20 blocks of travel. Emptiness is a bug.
2. **Mining is the verb, not scenery.** Ore is visible from corridors, rich in pockets, and hidden in vaults. Mining always pays.
3. **Every region has a different identity**, readable at a glance: block palette, light, sound, mobs, ores, hazards.
4. **Risk is visible before commitment.** Torches, tracks, bones, noise and glow warn before a fight or drop.
5. **Loot is found, not given.** Chests, corpses, vaults and boss drops, with rarity you can feel.
6. **Shared mine, shared story.** Everyone sees the same day's mine. "Did you find the vault in the east flooded gallery?" is the social hook, and it changes every dawn.
7. **Measured, not judged.** A `/dawnmine census` reports ore, mob, loot and landmark density per region. Targets below are acceptance criteria.

## 3. The mine, as a place (three descending levels)

One run is a descent of about 60-90 minutes for a solo player. Each level is a distinct place with its own "district" types.

### Level 1 - The Old Workings (Upper Galleries, stone)
Abandoned mining colony, mostly safe, teaches the game.
- **Spaces:** timbered galleries with rails, tool sheds, a collapsed lift shaft, wide natural caverns with glow-lichen, crystal clusters.
- **Ore:** coal, copper, iron, tin-class and other early pack ores, shown in exposed veins along every corridor.
- **Mobs:** vanilla undead and spiders in small groups; one Born in Chaos "elite" per district as a first test.
- **Loot:** miner's lockers, supply crates, ore carts with chests. Common tier, a few uncommon.
- **Landmark:** the Foreman's Office (rest point, notice board lore, first checkpoint).
- **Hook:** the first 5 minutes include ore in sight of spawn, one safe fight, and one visible locked or high-value thing the player cannot yet reach.

### Level 2 - The Deep Delve (Middle Descent, deepslate)
Older and wilder. Where the miners broke into something.
- **Spaces:** flooded galleries, fungal grottos, a huge vertical shaft with ledges and a rail spiral, buried ruins, narrow crawl tunnels.
- **Ore:** mid-tier pack ores in dense veins, plus **motherlode caverns** (rooms where walls are 20-30% ore).
- **Mobs:** mixed groups with a role (ranged + melee + one support), nests/spawner clusters guarded by a mini-boss, ambushes in side tunnels.
- **Loot:** vaults behind a puzzle or key found elsewhere on the level; uncommon to rare tier.
- **Landmark:** the Lift Hub (second checkpoint, shortcut rails back up).

### Level 3 - The Chaos Depths (deepslate, lava, quartz)
Dangerous, built for teams.
- **Spaces:** lava lakes with bridge routes, collapsing ledges, an arena cavern.
- **Ore:** top-tier ores, quartz, ancient debris-style rarity, very dense in guarded pockets.
- **Mobs:** Born in Chaos threats; coordinated-team boss encounter at the bottom (needs two or more roles).
- **Loot:** boss chest and rare vault tier; the only source of the best items.
- **Landmark:** the Heart (final checkpoint and bank/push decision).

## 4. Space structure (what replaces the node graph)

Not rooms-and-tubes. Layered generation, each layer with a clear job:

1. **Terrain layer:** 3D-noise carving gives organic caverns and winding worm tunnels (the Better Caves / cheese-and-spaghetti idea), per-level height bands so each level has a clear ceiling and floor and real vertical drops between them.
2. **Spine layer:** a guaranteed main route per level from entrance to descent, carved after terrain, so progress is never blocked.
3. **Districts layer:** a Poisson-disk placement of **points of interest** (POIs) along and off the spine. A POI is a small code-built set piece with a tag (mining camp, shrine, vault, nest, flooded hall, collapsed shaft, boss arena...). Each POI owns its ore, mobs and loot (see 5).
4. **Connectivity layer:** flood fill from the hub after everything is placed. Any standable area not reachable is either connected or sealed (replaces the whack-a-mole sealer from M10).
5. **Dressing layer:** timber, rails, lighting, props, cobwebs, bones along the routes that link POIs.

Constraint kept: the mine stays fully procedural from code and block layouts (AGENTS.md). Only the spawn hub may use an authored structure.

## 5. The three content systems (the actual fix)

### Ore
- **Veins:** noise-shaped clusters 6-40 blocks, region-specific ore tables, always partly exposed on a wall.
- **Guarantee:** every corridor segment of ~20 blocks has at least one exposed vein; the entrance route has ore in view.
- **Motherlodes:** 2-4 per level, a cavern rich in ore with guards.
- **Hidden ore:** behind cracked/suspicious walls, mining-only secrets.
- All 32 Rustic Craft 2 ores stay in the catalogue; native drops and break rules stay.
- Targets (tunable): exposed ore share of wall surface, L1 >= 6%, L2 >= 10%, L3 >= 14%; each ore type appears in its intended levels at least N times per run.

### Mobs
- Mobs are **placed at generation as encounters**, not just rolled at runtime. Types: patrol group, ambush, nest (spawner + guard), mini-boss, vault guardian, wandering elite.
- Each POI carries an encounter; spine and corridors get light ambient groups.
- Wake-up and leash rules so mobs do not pile up or lag the server (hard cap per chunk and per level; respawn only on daily reset, plus optional slow trickle in cleared rooms to stay dangerous).
- Targets (tunable): >= 1 encounter per 30-40 blocks of route, a mini-boss per district cluster, one team boss at the bottom.
- Keep: no PvP, no PvE in safe zones, Born in Chaos integration as verified.

### Loot
- Vanilla-style loot tables owned by the mod, five tiers: common, uncommon, rare, epic, boss.
- Sources: miner's lockers, supply crates, ore carts, dead-miner corpses (with a note that points at something), vaults, secret rooms, mini-boss and boss drops.
- Daily randomised placement; some loot is "keyed": a key or lever on one side of the level opens a vault on the other.
- Loot is mine-only, bounded, and mine-native (see section 10.5); it feeds the existing progression design.
- Targets: >= 1 loot spot per 40-60 blocks of route, >= 1 vault per level, rare tier only behind a guard or puzzle.

## 6. Navigation and readability
- Light is a language: warm torch trails near safe routes, cold light near danger, none in the deep.
- Rails and timber lead to POIs; dead ends are rare and always pay (a small cache or ore).
- Vertical layering: big shafts you can see across, ledges, and shortcut lifts unlocked by reaching a checkpoint.
- Checkpoints remain the bank-or-push choice (Foreman's Dispatch unchanged).

## 7. What we keep and what we scrap

**Scrap (generation layer):** `topology` graph planner, `SegmentGeometry` shapes, the room/tunnel node model, the ad-hoc pocket sealer, WallRoughness hacks, and the M10 test suite that tests them.

**Keep (infrastructure, already validated):** run lifecycle and daily reset, dimension and bedrock shell, protection rules, Dispatch lockers, teleporter, hub (authored structure plus fallback), ore catalogue and native-break audit, Born in Chaos integration, config, admin commands, whole-run pre-generation and chunk ticket management, CI and release pipeline.

## 8. How we prove it (so we stop guessing)
1. `/dawnmine census` reports per-level ore exposure %, encounter count, loot count, POI count, landmark spacing, longest empty stretch along the spine, unreachable standable cells (must be 0).
2. Unit tests assert census targets over many seeds.
3. Live test-server run: a walk-through using the census plus screenshots and the owner's own playtest. Player-perceived fun stays "owner verdict", never claimed by us.

## 9. Delivery plan (if approved)
- **R0** Census tooling first, run against the current mine to record the baseline (ore, mobs, loot are measurably low).
- **R1** Terrain + spine + connectivity (Level 1 only, empty but walkable).
- **R2** Ore system and motherlodes.
- **R3** POI framework with 6 Level 1 POI types, encounters and loot.
- **R4** Level 2, then **R5** Level 3 and the boss.
- **R6** Polish, tuning from census and owner playtest.
Each stage is its own PR and is playtested by the owner before the next begins.

## 10. Owner decisions (2026-11-11) and what they change

1. **Pacing:** per layer, about **15 min** if you know the way and rush; **30-45 min** if you really explore. Therefore each layer has **multiple distinct routes down** (target 3 or more, each with a different character: fast-but-dangerous, long-but-rich, hidden shortcut), side branches that loop, and far more content than the rush route touches. Footprint is sized from measured walking speed, not guessed; the census reports rush-route length and total explorable length per layer.
2. **Death/difficulty:** vanilla death rules stay. Level 1 is solo-safe; Level 3 is team-tuned.
3. **Reference feel:** the **vanilla Minecraft cave system** (big noise caves, ravines, lava lakes, dripstone/lush-style pockets) with **occasional landmarks in the player's way** drawn from vanilla gameplay (mineshafts, abandoned shafts with rails, dungeons/spawner rooms, geodes, fossils, buried ruins, flooded caves) plus a few of our own. Cave first, landmarks second. This replaces the "colony" framing in section 3: the miners' colony is now one landmark type among many, not the whole level.
4. **Authored set pieces:** allowed later. **Everything ships procedural first.** Each POI type has a procedural implementation and an optional authored override slot (same pattern as the spawn hub). **Builder tooling requirement:** setup commands must hand-hold a non-technical builder: `/dawnmine builder` lists which structure to build next in plain words ("Build: Foreman's Office. Stand here, build inside the marked box, run /dawnmine builder save"), shows the exact box size with markers/particles, validates and saves, explains any error in plain language, and shows progress ("3 of 8 done"). No command names or file paths for the builder to remember. This is its own milestone.
5. **Loot economy:** mine-native only: ores/raw ores, ingots-from-the-mine blocks, coal, gems, stone/deepslate/mineral blocks, and ordinary mining supplies (rails, torches, TNT, tools of mine tiers). No pack-specific treasure items, no special gear drops. Rarity tiers still exist but differ in **ore grade, quantity and block variety**, not in exotic items. Boss/vault rewards are big ore hauls and rare blocks. "You go to the mine to mine."
6. **Scrap scope:** confirmed. `topology`/`generation` are replaced; infrastructure in section 7 stays.

Open assumption to confirm later, not blocking: "ordinary mining supplies" in the loot tables.
