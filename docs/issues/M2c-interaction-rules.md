# M2c: Restrict mining to ores and placement to torches

### Planning metadata
- Issue type: Feature / player interaction protection
- Priority: High
- Milestone: M2c — Mine isolation and fair-play rules
- Assignee: Unassigned
- Target date: Not set

### Intent

Keep mining, route exploration, and combat as meaningful choices by preventing players from digging through walls, building shelters, blocking or trapping mobs, or otherwise bypassing encounters. Players should mine ore and use torches for light, while generated terrain remains intact.

### Expectation

Inside the active mine, survival players may break only catalogued mine ore blocks and may place only ordinary or soul torches as floor-mounted torches on valid top-facing surfaces. Valid ores continue to use their native harvest requirements and drops. Other block edits and common mechanics that would alter protected terrain or bypass route/encounter constraints are denied. Rules do not affect the factions mine or other worlds.

### Context that code cannot infer

- The screenshot request is: “players cannot break anything except ores and can only place torches,” to prevent avoiding mobs and mining without engaging the intended challenge.
- The exact Rustic Craft 2 pack ore inventory is established by M0 and completed in M3; the permission system must use the resulting canonical catalog, not a guessed vanilla-only list or broad name heuristic.
- The shared mine is server-authoritative. No client-only restriction is sufficient.
- Admin recovery and controlled setup remain available through existing permission-gated tools; public players must not gain an edit bypass.

### Scope

- Enforce mine-scoped server-side break permission: allow only block IDs explicitly registered in the completed pack-ore catalog and generated as mine ore.
- Preserve native harvest level/tool behavior, drops, silk-touch/fortune behavior, and event semantics for allowed ores.
- Enforce mine-scoped placement permission: allow ordinary and soul torches only when placed as floor torches on valid top-facing surfaces, with normal item consumption and placement validity; reject wall torches, redstone torches, and all other player-placed blocks.
- Prevent common player-triggered terrain bypasses (including explosions, pistons, fluids, dispensers, or equivalent events where enabled) from removing protected mine terrain, opening the shell, or creating non-ore mining opportunities.
- Keep sanctioned admin/setup/recovery paths explicitly permission-gated and auditable.
- Provide clear player feedback when an edit is denied, without chat spam on repeated attempts.

### Non-goals

No changes to global mining, block placement, vanilla harvest behavior, faction-mine protections, other dimensions, combat balance, mob AI, or new custom ore types. No disabling torches or other intended light placement.

### Dependencies and open decisions

- Depends on #4 (generated mine ownership/boundary) and #5 (complete pack ore catalog/depth mapping).
- Together with #22, blocks #6 (M4a encounters) from acceptance until both the boundary shell and interaction protections pass; combat tuning must assume players cannot bypass threats by reshaping terrain.
- Product owner: Codex. Implementer: Devin. Reviewer: independent.
- Exact allowed ore IDs must come from M3's verified catalog; unknown blocks fail closed as non-ore.

### Acceptance criteria

1. In the active mine, a survival player can break each catalogued mine ore under its native harvest requirements and receives its native drops.
2. Breaking stone, support geometry, shell blocks, utility blocks, or any non-catalogued block is denied without changing the world.
3. A survival player can place ordinary or soul torches on valid floor surfaces; wall torches, redstone torches, and all other placed blocks are denied and not consumed.
4. Common player-triggered mechanics cannot break protected non-ore terrain, bypass the shell, or create new safe tunnels/shelters around encounters.
5. Protection is server-side and applies only to the active mine's owned volume; the same actions retain expected behavior in the factions mine and other dimensions.
6. Existing permission-gated admin operations remain usable and cannot be invoked by ordinary players as a bypass.
7. Multiplayer and restart/reset checks show no race, duped drops, lost torches, stale protection bounds, or protection leak across mine runs.
8. Dedicated-server tests cover all pack ore families from M3, non-ore denial, floor-only torch allowlist, representative bypass mechanics, operator recovery, factions-mine controls, and user-facing denial feedback.

### Verification

Run the exact target pack dedicated server with a representative ore fixture from each catalog family. In survival, attempt allowed/denied breaks and placements, test enabled terrain-altering mechanics, and compare the same actions outside the mine. Repeat across two players, restart, and daily reset.

### PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, ownership, dependencies, commands/results, and residual risks.
