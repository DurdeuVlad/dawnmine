# Rustic Craft Public Mines

Standalone server-side NeoForge mod for a shared, Crystal Hollows-style public mining world in Rustic Craft 2.

The mine is a large, connected, procedurally generated expedition shared by all online players during a daily run. Players explore and choose routes, mine deposits, fight changing Born in Chaos threats, and decide when to bank or push deeper. Useful materials and equipment persist across daily resets so later runs can reach harder depths; the deepest content rewards coordinated teams. Checkpoints provide safe travel and private Foreman's Dispatch storage. Netherrack is off by default and can only be enabled through a bounded ultra-rare extreme-depth setting.

## Repository

Private GitHub repository: https://github.com/DurdeuVlad/rusticcraft-public-mines

## Project status

M0 foundation is merged to `main` (PR #15). The `m1-lifecycle-admin` branch adds the shared-run lifecycle and admin commands as dependent work on top of merged M0; see [docs/reports/M1-dedicated-server-lifecycle-evidence.md](docs/reports/M1-dedicated-server-lifecycle-evidence.md).

## Operator quick-start (M1)

Install `publicmines-<version>.jar` in the server's `mods/` folder alongside the Rustic Craft 2 pack and boot once. Then, in game or from the console:

1. `/publicmine setup validate` — shows exactly what's missing (dimension, run record, pad, exit) with a fix per line.
2. `/publicmine setup initialize` — creates the shared run: records the run id/seed, the active region, the arrival pad, and the surface exit. Safe to re-run; it will not duplicate state.
3. `/publicmine status` — confirm `READY`; the run is shared by every player.

Players use `/publicmine enter` and `/publicmine leave` (also `exit`).

If a boot ever interrupts setup, the next start marks the run `FAILED` with a plain-language reason; `/publicmine recover check` inspects it and `/publicmine recover repair` repairs only mine-owned state — nothing else is ever cleared, including the factions mine.

`/publicmine help` covers the same flow in game. Staff commands (`setup`, `config`, `recover`, detailed `status`) require operator level 2+; players get read-only help, a friendly status, and travel commands.

Config lives in `world/serverconfig/publicmines-server.toml` and is also changeable in game with `/publicmine config get` / `config set <key> <value>` (persisted immediately, with bounds and examples on rejection). Netherrack stays off by default; its interval cannot go below 256 eligible columns or above 65536.

## Target environment

- Rustic Craft 2 pack 2.2.2
- Minecraft 1.21.1
- NeoForge 21.1.248
- Java 21
- Born in Chaos 1.7.6 and GeckoLib 4.9.2 are listed in the local pack catalog

## Documents

- Business.md: player problem, intended experience, and success measures
- Decision.md: accepted decisions, proposals, and unresolved choices
- Milestones.md: ordered outcome plan and acceptance evidence
- Progression.md: player loop, skill/team challenge, lasting progression, extraction, admin UX, and playtest evidence contract
- Collaboration.md: product authority, implementation, and review expectations
- docs/issues/: executable issue contracts, beginning with M0

The existing KubeJS faction-mine system is outside this project and must remain unchanged.



