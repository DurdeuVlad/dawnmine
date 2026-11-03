# Security policy

## Scope

Dawnmine is a server-side Minecraft mod. Security-relevant surfaces: command permission gating (operator level 2+), safe-zone/protection enforcement, per-player Dispatch locker isolation, reset/evacuation correctness, and persistence integrity (`publicmines_run.dat`, `publicmines_dispatch.dat`).

## Reporting a vulnerability

Please **do not** open a public issue for exploitable bugs (e.g., item duplication, cross-player locker access, protection bypasses, permission escalation, world corruption outside mine-owned state).

Report privately via GitHub's private vulnerability reporting ("Security" tab → "Report a vulnerability") or by contacting the repository owner directly. Include: mod + pack versions, reproduction steps, and impact. We will acknowledge and work on a fix before disclosure.

## Supported versions

The latest release line (`0.2.x`) receives fixes; earlier builds are tester builds and are not maintained.
