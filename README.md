# Dawnmine

A standalone server-side NeoForge mod that gives every player on the server **one shared mine, fresh each dawn**.

The mine is a large, connected, procedurally generated expedition inside its own dimension (`dawnmine:mine`), enclosed in a generated bedrock shell. All online players share the same daily run: they explore generated routes, mine the pack's real ores, fight depth-scaled Born in Chaos threats, and choose when to bank or push deeper. Checkpoints provide safe travel and private persistent **Foreman's Dispatch** storage; useful materials and gear persist across daily resets so later runs reach harder depths. The deepest content is tuned for coordinated teams.

- **Shared run, one seed** — everyone descends the same mine; it is fully prepared before the first player enters.
- **Deeper = richer + deadlier** — three regions (Upper Galleries → Middle Descent → Chaos Depths) with all 32 Rustic Craft 2 ore types, quartz + contained lava at depth, and bounded mine-only combat rewards.
- **Daily 06:00 reset** — configurable time/timezone, occupant warnings, entry lock, evacuation with inventories, then exactly one new run.
- **Fair play enforced server-side** — no PvP anywhere in the mine; no PvE in safe zones; only catalogued mine ores breakable; only allowed floor torches placed.
- **Durable progression** — a pack-audited equipment/material graph where each upgrade solves a named earlier-depth problem.
- **Nothing outside the mine is ever touched** — cleanup is dimension- and volume-qualified; the factions mine and all other dimensions are out of reach by construction.

## Requirements

- Rustic Craft 2 pack **2.2.2** (designed and verified against it; may work on other 1.21.1 NeoForge packs — untested)
- Minecraft **1.21.1** · NeoForge **21.1.248** · Java **21**
- Server-side only — clients need no install.

## Install

1. Grab `dawnmine-<version>.jar` from [Releases](https://github.com/DurdeuVlad/dawnmine/releases) and verify the `.sha256`.
2. Drop it in the server's `mods/` folder alongside the pack and boot once.
3. In game or console, run **`/dawnmine setup guide`** — a state-aware walkthrough that marks each step `[done]`/`[next]`/`[todo]` and names the exact command to run. (Manual path: `setup validate` → `setup initialize` → `status`.)

Players use `/dawnmine enter`, `leave`, `checkpoints`, `checkpoint <id>`, `dispatch`, `progression`, `status`, `help`.

## Operations

- **Daily reset** — `resetHour:resetMinute` in `resetTimeZone` (default `06:00` host zone). Warnings at `resetWarningMinutes` (default `15,5,1`) go only to mine occupants; entry locks; occupants are evacuated with their inventories; recorded mine-owned terrain is restored and exactly one new run is created. Downtime produces exactly one catch-up reset. Staff: `reset status|now|retry`.
- **Config** — `world/serverconfig/dawnmine-server.toml` or `/dawnmine config get|set` (validated with bounds). Netherrack is off by default and can only be made *rarer* (interval floor 256 columns, cluster ≤3).
- **Recovery** — if a boot interrupts setup or a reset halts, `/dawnmine recover check` explains it and `/dawnmine recover repair` fixes only mine-owned state.
- Staff commands require operator level 2+; player commands are read-only/help + travel.

## Evidence

**Legacy 0.2 evidence** (Public Mines era, historical - not proof of the 0.3 build): 39/39 GameTests in the real dimension, live reset cycles, byte-identical locker persistence, a 60-case ore-break audit, 20.000 TPS on the exact pack. See `docs/reports/` (all legacy reports are dated 0.2). The 0.3 validation report is [`docs/reports/Dawnmine-0.3-validation.md`](docs/reports/Dawnmine-0.3-validation.md).

## Project docs

- `Business.md` / `Progression.md` — the experience contract and progression design
- `Milestones.md` / `docs/issues/` — milestone plan and per-issue contracts with acceptance evidence
- `docs/architecture/` — how the run lifecycle, generation, protection, reset, dispatch, and progression work
- `docs/art/` + `docs/concept-art/` — builder handoff concept art for the entrance hub and safe zones

## Contributing & license

Contributions welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). Security reports: [SECURITY.md](SECURITY.md). Licensed **LGPL-3.0-only** ([LICENSE](LICENSE)); the mod id is `dawnmine`, the dimension key is `dawnmine:mine`, and saved data lives in `dawnmine_run.dat`/`dawnmine_dispatch.dat`. The 0.3 rename is a breaking change - see `docs/Dawnmine-0.3-upgrade.md` before replacing a 0.2 install.

## Dawnmine 0.3

- Whole-run pre-generation: the entire mine (hub, passages, caverns, ores) is built before admission; nothing generates while players explore.
- Underground hub: a generated 45x45 stone-brick/deepslate expedition hub (spawn, Dispatch wing, portal hall, return console) replaces the old surface entry pad.
- Teleporter travel: staff bind one overworld lodestone with `/dawnmine teleporter bind <x> <y> <z>`; players right-click it to enter and right-click the hub's lodestone console to leave. The mod never builds or modifies overworld blocks.
- Design contract: `docs/Dawnmine-0.3-adventure-contract.md`; upgrade path: `docs/Dawnmine-0.3-upgrade.md`.
