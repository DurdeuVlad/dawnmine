# Dawnmine — The Endless Mine

**One shared mine, fresh each dawn. Everyone on the server descends the same expedition.**

Dawnmine turns your server into a daily expedition. Every 24 hours a brand-new underground world is generated inside its own dimension — real cave terrain with vanilla noise caves, carvers and aquifers, abandoned mineshafts, caverns, loops and vertical routes — and every player explores *the same* run together. The entire mine is fully prepared before the doors open: no terrain pop-in, no watching chunks generate. Just you, the dark, and a mine that feels endless.

Descend from a staff-bound lodestone into a concealed underground hub. Wind through lantern-lit passages and raw cave tunnels, cross open caverns and mineshaft webs, mine the pack's real ores where they belong by depth, and decide when to bank your haul at a checkpoint — or push deeper where the rewards get richer and the dark gets mean.

## Highlights

- **Shared daily run** — one procedural run seed for every player; today is never yesterday's mine
- **Real caves** — vanilla noise terrain, carvers, aquifers, mineshafts — not a flat stone canvas
- **Prepared before you enter** — all ~16,000 chunk columns generate before entry opens, plus silent look-ahead pre-generation as you move
- **Depth progression** — Upper Galleries → Middle Descent → Chaos Depths: rarer ores, quartz, lava pockets, deadlier encounters the lower you go
- **Checkpoints & banking** — safe alcoves to rest, travel, and stash loot in your private persistent **Foreman's Dispatch**
- **Daily 06:00 reset** — configurable schedule, warnings, evacuation with inventories intact, then exactly one new run
- **Server-side only** — players connect with a normal client; nothing to install, nothing to sync
- **Respects your world** — the mine lives in its own dimension; the Overworld, spawn, and your other dimensions are never touched

## Optional structure integrations

Dawnmine can place structures from these mods inside the mine — configure via `structureSites` in `dawnmine-common.toml` (`modid:structure_id=count`). Placement is deterministic per run, deep-underground only, and **no-cut guaranteed**: candidates are rejected if they'd touch corridors, rooms, the hub, checkpoints, safe zones, or ore veins. Missing mods are skipped cleanly.

| Mod | CurseForge |
|---|---|
| YUNG's Better Mineshafts (NeoForge) | https://www.curseforge.com/minecraft/mc-mods/yungs-better-mineshafts-neoforge |
| YUNG's Better Dungeons (NeoForge) | https://www.curseforge.com/minecraft/mc-mods/yungs-better-dungeons-neoforge |
| Repurposed Structures (NeoForge/Forge) | https://www.curseforge.com/minecraft/mc-mods/repurposed-structures |
| YUNG's API (NeoForge) — required library | https://www.curseforge.com/minecraft/mc-mods/yungs-api-neoforge |
| Valhelsia Structures | https://www.curseforge.com/minecraft/mc-mods/valhelsia-structures |

Vanilla mineshafts are always included. Structure loot chests are restocked with area-appropriate ores only.

## Getting started (server admins)

1. Drop `dawnmine-<version>.jar` into the server's `mods/` folder and boot once.
2. In game or console run **`/dawnmine setup guide`** — an interactive walkthrough (`validate` → `initialize` → `status`).
3. Bind the surface teleporter with **`/dawnmine teleporter bind <x> <y> <z>`** near a lodestone — players touch it to descend.
4. Done. The mine resets daily at 06:00 (configurable) and regenerates itself.

## Players

- **`/dawnmine enter`** — travel into the shared mine (or touch the surface teleporter)
- **`/dawnmine checkpoints`** / **`/dawnmine checkpoint <id>`** — safe-zone travel
- **`/dawnmine dispatch`** — open your private Foreman's Dispatch at a checkpoint
- **`/dawnmine leave`** — emergency return to the surface
- **`/dawnmine progression`** — see what your finds can be forged into

## Requirements

- Minecraft **1.21.1** · NeoForge **21.1.248+** · Java **21**
- **Server-side only** — no client install required
- Designed and verified on the Rustic Craft 2 pack; the ore catalog and structure list are config-driven for other NeoForge 1.21.1 packs

## FAQ

**Does it change the Overworld or other dimensions?** No — everything lives in `dawnmine:mine`. Cleanup is dimension- and volume-qualified by construction.

**Do players lose items on reset?** No — evacuations keep inventories; the Foreman's Dispatch persists across resets.

**Can I use it without Rustic Craft 2?** It was built and verified on RC2. Other 1.21.1 NeoForge packs can map the ore catalog via config — untested, reports welcome.

---

**Source & issues:** https://github.com/DurdeuVlad/dawnmine · **License:** LGPL-3.0
