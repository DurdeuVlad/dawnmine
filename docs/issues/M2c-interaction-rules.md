# M2c: Protect mine interactions and disable PvP

### Planning metadata
- Issue type: Feature / player interaction protection
- Priority: High
- Milestone: M2c — Mine isolation and fair-play rules
- Assignee: Unassigned
- Target date: Not set

### Intent

Keep mining and combat meaningful by preventing terrain exploits and player-versus-player combat in the shared mine. Designated safe zones must also prevent hostile mobs and players from fighting there, including attacks made across the safe-zone boundary.

### Expectation

Inside the active mine, survival players may break only catalogued mine ore blocks and may place only ordinary or soul torches as floor-mounted torches on valid top-facing surfaces. No player may harm another player anywhere in the active mine, including damage, knockback, harmful status effects, or other harmful effects. In explicitly registered safe zones, hostile-to-player and player-to-hostile harm is blocked, and combat effects cannot cross the zone boundary. Safe-zone placement and hostile encounter behavior are completed by M5a (#8) and M4a (#6). Rules do not affect the factions mine or other worlds.

### Context that code cannot infer

- The screenshot request is: players cannot break anything except ores and can only place torches, to prevent avoiding mobs and mining without engaging the intended challenge.
- The user explicitly requires no PvP anywhere in the mine and no PvE in safe zones.
- The exact Rustic Craft 2 pack ore inventory is established by M0 and completed in M3; use its canonical catalog, not a guessed vanilla-only list or broad name heuristic.
- The shared mine is server-authoritative. No client-only restriction is sufficient.
- Admin recovery and controlled setup remain available through existing permission-gated tools; public players must not gain an edit or combat bypass.

### Scope

- Enforce mine-scoped server-side break permission: allow only block IDs explicitly registered in the completed pack-ore catalog and generated as mine ore.
- Preserve native harvest level/tool behavior, drops, silk-touch/fortune behavior, and event semantics for allowed ores.
- Enforce mine-scoped placement permission: allow ordinary and soul torches only when placed as floor torches on valid top-facing surfaces, with normal item consumption and placement validity; reject wall torches, redstone torches, and all other player-placed blocks.
- Prevent common player-triggered terrain bypasses (including explosions, pistons, fluids, dispensers, or equivalent events where enabled) from removing protected mine terrain, opening the shell, or creating non-ore mining opportunities.
- Deny player-caused harm to another player anywhere in the active mine, including direct attacks, projectiles, area effects, knockback, harmful status effects, player-owned entities, and player-originated hazards.
- Provide the generic server-side safe-zone registration and combat-guard contract/API for run-owned volumes, including test-fixture registration. Prevent hostile-to-player and player-to-hostile damage/effects from crossing either direction. M4a (#6) consumes this contract to exclude hostile mobs; M5a (#8), which depends on #6, registers the concrete surface/checkpoint/Dispatch zones.
- Keep sanctioned admin/setup/recovery paths explicitly permission-gated and auditable.
- Provide clear player feedback when an edit or PvP attempt is denied, without chat spam on repeated attempts.

### Non-goals

No changes to global mining, block placement, PvP/PvE rules outside Public Mines, vanilla harvest behavior, faction-mine protections, other dimensions, mob AI, or new custom ore types. No disabling torches or other intended light placement.

### Dependencies and open decisions

- Depends on #4 (generated mine ownership/boundary), #5 (complete pack ore catalog/depth mapping), and #22 (mine boundary shell).
- This issue owns the generic run-scoped safe-zone registry contract/API and server-side damage/effect protection. M4a (#6) consumes that contract to keep hostile mobs out; M5a (#8) depends on both and registers concrete station-associated volumes.
- Together with #22, blocks #6 (M4a encounters) from acceptance until both the boundary shell and interaction protections pass.
- Product owner: Codex. Implementer: Devin. Reviewer: independent.
- Exact allowed ore IDs must come from M3's verified catalog; unknown blocks fail closed as non-ore. Safe-zone shapes are explicit station-associated volumes, not an assumed radius.

### Acceptance criteria

1. In the active mine, a survival player can break each catalogued mine ore under its native harvest requirements and receives its native drops.
2. Breaking stone, support geometry, shell blocks, utility blocks, or any non-catalogued block is denied without changing the world.
3. A survival player can place ordinary or soul torches on valid floor surfaces; wall torches, redstone torches, and all other placed blocks are denied and not consumed.
4. No player can harm another player anywhere in the active mine through damage, knockback, harmful status effects, or other effects from direct, projectile, area, pet/tamed-entity, or player-originated environmental sources.
5. In registered safe zones, hostile damage/effects cannot reach players, players inside cannot harm hostile mobs outside, and player/mob combat effects cannot cross the zone boundary in either direction.
6. Protection is server-side and scoped to the active mine and explicitly registered mine safe zones; the same actions retain expected behavior in the factions mine and unrelated worlds.
7. Existing permission-gated admin operations remain usable and cannot be invoked by ordinary players as a bypass.
8. Multiplayer and restart/reset checks show no race, duped drops, lost torches, stale mine/safe-zone bounds, or protection leak across mine runs.
9. Dedicated-server tests cover all pack ore families from M3, non-ore denial, floor-only torch allowlist, PvP sources, safe-zone damage boundaries both ways, representative bypass mechanics, operator recovery, factions-mine controls, and user-facing denial feedback.

### Verification

Run the exact target pack dedicated server with a representative ore fixture from each catalog family and two or more players. Attempt allowed/denied breaks and placements, PvP damage through each supported source, and hostile/player damage across registered safe-zone boundaries. Compare behavior in the factions mine and another dimension. Repeat across restart and daily reset.

### PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, ownership, dependencies, commands/results, and residual risks.
