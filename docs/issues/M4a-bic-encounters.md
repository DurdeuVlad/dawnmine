# M4a Issue Draft — Born in Chaos depth encounters

## Planning metadata
- Issue type: Feature / optional-mod integration
- Priority: High
- Milestone: M4 — Born in Chaos depth encounters and rewards
- Assignee: Unassigned
- Target date: Not set

## Intent

Give deeper mine regions stronger and more varied combat through readable threat cues, changing tactics, and coordinated play—not only larger health/damage—using Born in Chaos entities already present in the target Rustic Craft pack.

## Expectation

The mine chooses only entities verified from the exact pack artifact, places encounters at M2's validated encounter sites, and changes encounter composition/tactics with region depth. Encounters test movement, target choice, timing, route awareness, and team coordination using verified entity behavior and bounded environmental pressure. Creation and cleanup are mine-specific and bounded.

## Context that code cannot infer

- Target pack catalog lists Born in Chaos 1.7.6 and GeckoLib 4.9.2; M0 must verify exact jar and IDs.
- M0 now identifies `born_in_chaos_v1:diamond_termite` as a verified pack registry entry. The infected diamond ore player-break procedures call Diamond Termite spawn paths; the ore tables yield no direct items in the tested tool context, while termite shards have a nine-to-one diamond recipe. See the [M0 runtime encounter evidence](../reports/M0-native-loot-smoke.md). This is a promising ambush-and-material loop, but actual player behavior remains untested.
- Born in Chaos AI and drops outside the mine must remain unchanged.
- Some BiC mobs have special abilities that may be unsafe in confined tunnels; candidates need review and playtest.
- The product contract rejects difficulty based only on inflated mob stats. Attack/hazard cues must be readable and provide fair counterplay.

## Scope

- Implement a data-driven depth-to-entity encounter table from verified registry IDs.
- For every composition, document the verified threat, which tactic it challenges, visible cues/counterplay, and why it belongs at that depth.
- Later encounters should pressure static ranged kiting/tunnel camping through verified abilities, route choices, varied threats, or bounded hazards while preserving counterplay.
- Design the deepest intended encounter around simultaneous demands that materially benefit a coordinated group. Do not claim team-required difficulty until M8 playtests compare solo and group outcomes.
- Check mod/artifact availability and fail closed with a clear diagnostic if a configured entity is missing.
- Spawn only at validated M2 sites with safe floor/headroom and a per-region/population budget.
- Persist mine-origin identity for runtime cleanup and M4b reward handling.
- If generated BiC blocks can summon mobs through their native break hooks, capture those resulting entities in the same mine-origin lifecycle so reset cleanup remains bounded; do not change behavior outside the mine.
- Prevent duplicates after restart and clean up on run reset.

## Non-goals

No global spawn-rule changes, BiC class coupling, rebalancing BiC AI/attributes/loot, or unverified bosses.

## Dependencies and open decisions

- Depends on M0 exact artifact report, M1 lifecycle, and M2 encounter points.
- M4b consumes mine-origin tracking.
- Mobs with unsafe tunneling/explosion/large-area behavior remain excluded unless a test proves the configured encounter is safe.
- Product owner: Codex. Implementer: Devin. Reviewer: independent.

## Acceptance criteria

1. Every spawn-table entity ID exists in the target runtime registry and is recorded with artifact version evidence.
2. Deeper tiers produce distinct threat/tactic combinations within measurable mob and hazard caps; raw health/damage scaling alone fails acceptance.
3. Invalid IDs, missing BiC, or bad spawn surfaces do not crash the dedicated server or spawn substitutes silently.
4. Spawned mobs are tracked as mine-owned, are cleaned on reset/recovery, and cannot accumulate without bound.
5. Natural BiC spawns outside the mine retain unchanged spawn/AI/drop behavior.
6. If infected diamond ores are included, player-break tests prove their summoned Diamond Termites are tracked, contained, reset-cleaned, and reward shards as expected; otherwise those blocks are excluded from mine generation until the hook is safely integrated.
7. Tests cover missing entity IDs, bad spawn positions, cap enforcement, restart, cleanup, counterplay/reaction windows, and deepest-tier solo/group behavior.

## Verification

Run a dedicated server with the pinned pack artifact. Inspect each region's encounter IDs and positions, test selected mobs in generated corridors, and compare natural outside-mine behavior before/after.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
