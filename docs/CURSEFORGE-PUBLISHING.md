# CurseForge publishing — one-time setup (~3 min in browser)

Everything is staged. You only need a CurseForge account; the page is already logged in on your Chrome.

## 1. Create the project

Go to https://www.curseforge.com/minecraft/mc-mods → profile menu → **Create a Project**.

| Field | Value |
|---|---|
| Name | `Dawnmine` |
| Summary | `One shared mine, fresh each dawn — a fully pre-generated cave expedition for every player on the server.` |
| Description | Paste the full contents of `docs/curseforge-description.md` |
| Avatar / logo | `src/main/resources/logo.png` (already in this repo and inside the jar) |
| Categories | World Generation, Adventure and RPG, Server Utility |
| Environment | Server |
| License | GNU Lesser General Public License 3.0 (LGPL-3.0) |
| Project links | Source: `https://github.com/DurdeuVlad/dawnmine` · Issues: `https://github.com/DurdeuVlad/dawnmine/issues` |
| Minecraft versions | 1.21.1 |
| Mod loaders | NeoForge |

## 2. First file upload

On the project page → **Files** → **Upload File**:

- File: `build/libs/dawnmine-0.4.0.jar` (includes the icon + metadata)
- Display name: `dawnmine-0.4.0.jar`
- Release type: `beta` (or `release`)
- Game versions: `1.21.1`, `NeoForge`, `Java 21`
- Changelog: paste `docs/curseforge-description.md` top section or a short blurb
- Optional dependencies (Relations tab): `yungs-better-mineshafts-neoforge`, `yungs-better-dungeons-neoforge`, `repurposed-structures`, `yungs-api-neoforge`, `valhelsia-structures`

First-file moderation usually clears within a day.

## 3. One-command future releases

After the project exists:

1. console.curseforge.com → **API tokens** → generate a token → `set CURSEFORGE_TOKEN=...`
2. `gradlew -Pcurseforge_project_id=<numeric id> curseforge`

The build.gradle block uploads `dawnmine-<mod_version>.jar` with all game-version flags and optional-dependency relations automatically. No secrets are stored in the repo.
