# M4a Issue Draft — Born in Chaos depth encounters

## Planning metadata
- Issue type: Feature / optional-mod integration
- Priority: High
- Milestone: M4 — Born in Chaos depth encounters and rewards
- Assignee: Unassigned
- Target date: Not set

## Intent

Give deeper mine regions stronger and more varied combat by using Born in Chaos entities already present in the target Rustic Craft pack.

## Expectation

The mine chooses only entities verified from the exact pack artifact, places encounters at M2's validated encounter sites, and scales encounter composition/population with region depth. Entity creation and cleanup are bounded and mine-specific.

## Context that code cannot infer

- Target pack catalog lists Born in Chaos 1.7.6 and GeckoLib 4.9.2; M0 must verify exact jar and IDs.
- Born in Chaos AI and drops outside the mine must remain unchanged.
- Some BiC mobs have special abilities that may be unsafe in confined tunnels; candidates need review and playtest.

## Scope

- Implement a data-driven depth-to-entity encounter table from verified registry IDs.
- Check mod/artifact availability and fail closed with a clear diagnostic if a configured entity is missing.
- Spawn only at validated M2 sites with safe floor/headroom and a per-region/population budget.
- Persist mine-origin identity for runtime cleanup and M4b reward handling.
- Prevent duplicates after restart and clean up on run reset.

## Non-goals

No global spawn-rule changes, BiC class coupling, rebalancing BiC AI/attributes/loot, or unverified bosses.

## Dependencies and open decisions

- Depends on M0 exact artifact report, M1 lifecycle, and M2 encounter points.
- M4b consumes mine-origin tracking.
- Mobs with unsafe tunneling/explosion/large-area behavior remain excluded unless a test proves the configured encounter is safe.
- Owner: server owner. Implementer: Devin. Reviewer: independent.

## Acceptance criteria

1. Every spawn-table entity ID exists in the target runtime registry and is recorded with artifact version evidence.
2. Deeper tiers produce the agreed stronger encounter table within a measurable population cap.
3. Invalid IDs, missing BiC, or bad spawn surfaces do not crash the dedicated server or spawn substitutes silently.
4. Spawned mobs are tracked as mine-owned, are cleaned on reset/recovery, and cannot accumulate without bound.
5. Natural BiC spawns outside the mine retain unchanged spawn/AI/drop behavior.
6. Tests cover missing entity IDs, bad spawn positions, cap enforcement, restart, and cleanup.

## Verification

Run a dedicated server with the pinned pack artifact. Inspect each region's encounter IDs and positions, test selected mobs in generated corridors, and compare natural outside-mine behavior before/after.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
