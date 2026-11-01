# Business and product brief

## Problem

Players describe repetitive strip mining as unengaging. The server needs a public mining activity that rewards exploration and combat as well as block mining, without replacing the existing factions mine.

## Users and value

- Players want a shared adventure that is easy to enter, rewarding to explore, and worth revisiting.
- Server operators need a bounded, recoverable activity that resets on a predictable schedule and does not damage unrelated worlds or faction-mine state.
- The mod is specific to the Rustic Craft 2 server pack, so its ore and mob progression should reflect the actual installed content.

## Intended experience

Players enter one shared, daily seeded Crystal Hollows-style expedition. They learn the upper mine, choose routes with different resource/risk profiles, mine deposits, fight dangerous encounters, and decide when to bank or push farther. Useful materials and equipment persist between runs so players can prepare for deeper regions on later days. Deeper regions introduce new tactical problems as earlier upgrades solve old ones; the deepest intended content should demand team coordination. The Chaos Depths use Nether materials such as quartz and contained lava hazards. Netherrack is disabled by default; if enabled, allow at most one placement attempt per 256 eligible 16×16 chunk columns in the deepest region, with one cluster up to three blocks, and permit operators only to make it rarer.

Players reach safe checkpoints that support travel between the mine and the surface and open their personal Foreman's Dispatch locker. The locker has materially more capacity than a vanilla Ender Chest, supports depositing and retrieving items, and remains available outside the mine. At the configured morning reset, the server warns and evacuates players, creates a new shared layout, and preserves carried and stored player items.

The mine uses generated block geometry. The experience may have recognizable regions, checkpoints, and service stations, but does not rely on predefined building structures. Difficulty comes from readable mechanics, route and hazard decisions, varied verified encounters, and teamwork—not only inflated health or damage.

## Product rules

- The mine is public and shared; every player sees the same active run and layout.
- Layout variation is stable for the whole daily run. Players do not receive private copies.
- Generation progresses as the community explores and remains within explicit server-controlled bounds and budgets.
- Terrain, mobs, loose drops, and run-local checkpoints reset daily, while carried equipment, progression materials, and private Dispatch contents persist.
- Mine progression links mining, combat, and exploration to pack-verified recipes/equipment. Each upgrade has a documented earlier-depth problem it helps solve and a later challenge it does not trivialize.
- Checkpoints are safe bank-and-travel decisions: players can deposit into their private persistent Dispatch and choose whether to push farther. The first version preserves vanilla death/inventory behavior and adds no mod-specific death penalty.
- Deep difficulty is measured in solo and group playtests; the deepest intended tier must reward coordination and cannot be deemed team-required from config alone.
- Every ore in the target pack is assigned to a depth tier. Ordinary and modded ores retain their native blocks, mining requirements, and drops. Nether ores such as quartz belong in the Chaos Depths.
- Netherrack generation is off by default. If an operator enables it, hard validation bounds it to very rare generation at extreme depth.
- Mine-specific mob rewards are awarded only for mine encounters. Born in Chaos behavior outside the mine remains unchanged.
- A checkpoint is a safe, in-world point with travel and Dispatch access, not a prefab building.
- The morning reset clears mine-owned terrain, mobs, loose drops, and run progress. It preserves player inventories and personal Dispatch storage.
- Existing factions-mine claims, progression, access, generation, and reset behavior are not part of this mod.

## Success measures

- A dedicated server loads the mod with the target Rustic Craft 2 pack.
- All pack ores found in runtime ore tags or the explicit pack audit are accounted for and appear in their configured depth tiers.
- Multiple players share the same generated layout, frontier, and checkpoints during a run.
- Mine encounters increase in difficulty with depth; mine rewards do not change Born in Chaos drops outside the mine.
- Repeatable playtests show readable early learning, meaningful gear progression, varied mid/deep challenges, and a material teamwork advantage at the deepest intended encounters.
- A new operator can use in-game help and permission-gated setup/config/status/validation/recovery commands, and players receive friendly targeted messages without reading source code.
- Players can enter, checkpoint-travel, evacuate, and recover items through personal Dispatch storage.
- The morning cycle resets the mine once, safely handles online and offline cases, and does not lose player storage or affect the factions mine.
- Generation, reset, and encounters remain within documented server time, memory, entity-count, and world-bound limits.

## Assumptions and open product choices

- Morning reset defaults to 06:00 in the configured server timezone and is admin-adjustable through validated server config; in-game status shows the effective next reset.
- Foreman's Dispatch uses exactly 54 private slots in the first version, twice vanilla Ender Chest capacity; there is no paid or upgrade expansion.
- The exact world dimension/region strategy and daily generation bounds are technical decisions for M0/M1 based on server-safe evidence.
- The target ore catalog and safe Born in Chaos encounter set must be verified from the actual pack artifacts.
- Death/respawn keeps vanilla behavior in the first version; pressure comes from the choice to bank or continue and the risk of the mine. Any extra item-loss rule requires a product decision.
- Upgrade recipes and named gear tiers must be selected from the actual pack audit. User examples such as steel or silver are cues, not presumed pack facts.


## Evidence and references

- The target pack is identified by C:\Users\Vlad\Documents\Github\rustic-craft-2-modpack\modpack.json (Rustic Craft 2 2.2.2, Minecraft 1.21.1, NeoForge 21.1.248); the matching catalog lists Born in Chaos 1.7.6 and GeckoLib 4.9.2.
- The Crystal Hollows reference is described as a randomly generated mining location with multiple sub-locations: https://hypixelskyblock.minecraft.wiki/w/Crystal_Hollows
- NeoForge 1.21.1 Java 21 project setup: https://docs.neoforged.net/docs/1.21.1/gettingstarted/
- NeoForge common tag model: https://docs.neoforged.net/docs/1.21.1/resources/server/tags/
- Born in Chaos version/platform metadata: https://modrinth.com/mod/borninchaos/versions
