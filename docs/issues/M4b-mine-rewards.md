# M4b Issue Draft — Mine-only combat ore rewards

## Planning metadata
- Issue type: Feature / reward handling
- Priority: Medium
- Milestone: M4 — Born in Chaos depth encounters and rewards
- Assignee: Unassigned
- Target date: Not set

## Intent

Make combat a useful contribution to durable equipment progression alongside mining and exploration, while preventing unrelated Born in Chaos kills from changing server-wide drops.

## Expectation

A player/team who defeats an eligible mine-owned encounter can receive bounded, bankable materials useful to an audited upgrade path at that depth. Combat rewards complement rather than dominate mining and exploration. Existing entity loot remains intact. Kills outside the mine, non-player deaths, duplicate death events, and reset cleanup do not grant the mine bonus.

## Context that code cannot infer

- Reward intent: players may obtain ores either by mining blocks or killing mobs.
- Mobs are selected and tagged in M4a; ore tiers are catalogued in M3.
- All mining and mob rewards affect server economy; values require playtest review.
- M6 maps rewards to durable upgrades and documents source/yield/sink; rewards must not be filler or an infinitely repeatable farm.

## Scope

- Track encounter depth/tier on mine-spawned entities.
- Add one server-authoritative death reward path for player-caused eligible kills.
- Use M3's ore/reward catalog; bound stack size and chance per kill.
- Tag each reward to an M6 progression purpose and ensure mining/exploration have a meaningful parallel contribution to the same group capability.
- Make reward processing idempotent and auditable in logs/debug mode.

## Non-goals

No loot-table mutation for global BiC entities, no rewards for arbitrary renamed/tagged mobs, no currency system, and no changes to standard Minecraft death drops.

## Dependencies and open decisions

- Depends on M3 ore tiers and M4a mine-origin entity tracking.
- Exact yield/chance values are open until a dedicated-server playtest; use conservative configurable defaults and require product-owner review before release.
- Product owner: Codex. Implementer: Devin. Reviewer: independent.

## Acceptance criteria

1. Eligible player kills of mine-owned mobs produce only the configured reward for that encounter tier.
2. Natural, non-player-killed, despawned, or non-mine mobs receive no mine bonus.
3. Duplicate event delivery cannot duplicate the reward.
4. Native drops remain present and unchanged.
5. Tests cover kill attribution, depth tier, missing reward catalog entries, duplicate events, and out-of-mine counterexamples.
6. Each configured reward has a verified M6 progression use and documented depth/source/yield; no reward is dead-end filler.
7. Reward rates are exposed for admin review and tested in pack context before release; encounter grinding is not a faster substitute for all mining/exploration.

## Verification

In dedicated-server play tests, kill mine and natural BiC mobs at multiple depths, kill mobs with environmental damage and player damage, and compare reward inventories and native drops.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
