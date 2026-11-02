# Business and product brief

## Problem

Players describe repetitive strip mining as unengaging. The server needs a public mining activity that rewards exploration and combat as well as block mining, without replacing the existing factions mine.

## Users and value

- Players want a shared adventure that is easy to enter, rewarding to explore, and worth revisiting.
- Server operators need a bounded, recoverable activity that resets on a predictable schedule and does not damage unrelated worlds or faction-mine state.
- The mod is specific to the Rustic Craft 2 server pack, so its ore and mob progression should reflect the actual installed content.

## Intended experience

Players enter one shared, daily seeded Crystal Hollows-style mining world. They explore interconnected procedural caves, branches, landmarks, resource pockets, and encounters. The world grows as players reach its frontier. Deeper regions contain progressively more valuable pack ores and tougher Born in Chaos mobs. The Chaos Depths use Nether materials, including quartz, and contained lava hazards.

Players reach safe checkpoints that support travel between the mine and the surface and open their personal Foreman's Dispatch locker. The locker has materially more capacity than a vanilla Ender Chest, supports depositing and retrieving items, and remains available outside the mine. At the configured morning reset, the server warns and evacuates players, creates a new shared layout, and preserves carried and stored player items.

The mine uses generated block geometry. The experience may have recognizable regions and landmarks, but does not rely on predefined building structures.

## Product rules

- The mine is public and shared; every player sees the same active run and layout.
- Layout variation is stable for the whole daily run. Players do not receive private copies.
- Generation progresses as the community explores and remains within explicit server-controlled bounds and budgets.
- Every ore in the target pack is assigned to a depth tier. Ordinary and modded ores retain their native blocks, mining requirements, and drops. Nether ores such as quartz belong in the Chaos Depths.
- Mine-specific mob rewards are awarded only for mine encounters. Born in Chaos behavior outside the mine remains unchanged.
- A checkpoint is a safe, in-world point with travel and Dispatch access, not a prefab building.
- The morning reset clears mine-owned terrain, mobs, loose drops, and run progress. It preserves player inventories and personal Dispatch storage.
- Existing factions-mine claims, progression, access, generation, and reset behavior are not part of this mod.

## Success measures

- A dedicated server loads the mod with the target Rustic Craft 2 pack.
- All pack ores found in runtime ore tags or the explicit pack audit are accounted for and appear in their configured depth tiers.
- Multiple players share the same generated layout, frontier, and checkpoints during a run.
- Mine encounters increase in difficulty with depth; mine rewards do not change Born in Chaos drops outside the mine.
- Players can enter, checkpoint-travel, evacuate, and recover items through personal Dispatch storage.
- The morning cycle resets the mine once, safely handles online and offline cases, and does not lose player storage or affect the factions mine.
- Generation, reset, and encounters remain within documented server time, memory, entity-count, and world-bound limits.

## Assumptions and open product choices

- Product decision: the configurable morning reset defaults to 06:00 in the configured server timezone. Operators can change the supported schedule in game; the effective time and timezone must be visible.
- Product decision: Foreman's Dispatch has at least 54 slots in the first version. Expansion rules are deferred; its capacity must remain greater than a vanilla Ender Chest.
- The exact world dimension/region strategy and daily generation bounds are technical decisions for M0/M1 based on server-safe evidence.
- The target ore catalog and safe Born in Chaos encounter set must be verified from the actual pack artifacts.
- Death, item-loss, and checkpoint respawn rules remain unchanged from the pack's vanilla behavior in v1; do not add a mine-specific death penalty during implementation.


## Evidence and references

- The target pack is identified by C:\Users\Vlad\Documents\Github\rustic-craft-2-modpack\modpack.json (Rustic Craft 2 2.2.2, Minecraft 1.21.1, NeoForge 21.1.248); the matching catalog lists Born in Chaos 1.7.6 and GeckoLib 4.9.2.
- The Crystal Hollows reference is described as a randomly generated mining location with multiple sub-locations: https://hypixelskyblock.minecraft.wiki/w/Crystal_Hollows
- NeoForge 1.21.1 Java 21 project setup: https://docs.neoforged.net/docs/1.21.1/gettingstarted/
- NeoForge common tag model: https://docs.neoforged.net/docs/1.21.1/resources/server/tags/
- Born in Chaos version/platform metadata: https://modrinth.com/mod/borninchaos/versions
