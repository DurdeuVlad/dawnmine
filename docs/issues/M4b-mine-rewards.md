# M4b Issue Draft — Mine-only combat ore rewards

## Planning metadata
- Issue type: Feature / reward handling
- Priority: Medium
- Milestone: M4 — Born in Chaos depth encounters and rewards
- Assignee: Unassigned
- Target date: Not set

## Intent

Make combat a valid alternative to mining for resources, as requested, while preventing unrelated Born in Chaos kills from changing server-wide drops.

## Expectation

A player who kills an eligible mine-owned mob can receive a bounded ore/salvage reward appropriate to the current depth. Existing entity loot remains intact. Kills outside the mine, non-player deaths, duplicate death events, and reset cleanup do not grant the mine bonus.

## Context that code cannot infer

- Reward intent: players may obtain ores either by mining blocks or killing mobs.
- Mobs are selected and tagged in M4a; ore tiers are catalogued in M3.
- All mining and mob rewards affect server economy; values require playtest review.

## Scope

- Track encounter depth/tier on mine-spawned entities.
- Add one server-authoritative death reward path for player-caused eligible kills.
- Use M3's ore/reward catalog; bound stack size and chance per kill.
- Make reward processing idempotent and auditable in logs/debug mode.

## Non-goals

No loot-table mutation for global BiC entities, no rewards for arbitrary renamed/tagged mobs, no currency system, and no changes to standard Minecraft death drops.

## Dependencies and open decisions

- Depends on M3 ore tiers and M4a mine-origin entity tracking.
- Exact yield/chance values are open until a dedicated-server playtest; use conservative configurable defaults and require owner review before release.
- Owner: server owner. Implementer: Devin. Reviewer: independent.

## Acceptance criteria

1. Eligible player kills of mine-owned mobs produce only the configured reward for that encounter tier.
2. Natural, non-player-killed, despawned, or non-mine mobs receive no mine bonus.
3. Duplicate event delivery cannot duplicate the reward.
4. Native drops remain present and unchanged.
5. Tests cover kill attribution, depth tier, missing reward catalog entries, duplicate events, and out-of-mine counterexamples.
6. Reward rates are exposed for owner review and tested in pack context before release.

## Verification

In dedicated-server play tests, kill mine and natural BiC mobs at multiple depths, kill mobs with environmental damage and player damage, and compare reward inventories and native drops.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
