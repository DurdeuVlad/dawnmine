# M4a: Born in Chaos depth encounters

## Intent

Give deeper Public Mine regions stronger and more varied combat through readable threats and counterplay using only entity IDs verified against the pinned Rustic Craft pack.

## Scope

- Define weighted, data-driven encounter compositions for the upper, middle, and Chaos topology regions.
- Spawn only after a mine room segment has been generated, at an owned, supported, clear position outside run-owned safe zones.
- Skip checkpoints and non-combat pockets. Resolve each generated node once per run so replay after restart cannot duplicate an encounter.
- Enforce per-region and global active-mob caps. Keep mobs at their native attributes and behavior; do not scale raw health or damage.
- Persist mine-origin identity, region, entity ID, and source on mobs and in run data. Include native hostile spawns such as Diamond Termites summoned by infected ores.
- Prevent hostile entities from joining, entering, or remaining in registered safe zones. Existing M2c damage/effect rules protect both directions across their boundaries.
- Remove loaded mobs from an ending run and reject stale-run mobs when their chunks load later.
- Fail closed with a diagnostic if a required entity is not registered or does not create a hostile Mob. Do not silently substitute another entity.

## Verified entity set and design intent

The initial table uses `born_in_chaos_v1:decrepit_skeleton` (ground melee pursuer) and `born_in_chaos_v1:bone_imp` (faster, armored, fire-immune melee attacker). The deepest table mixes them to split target attention and reward coordinated focus. Generated infected diamond ores retain their native `born_in_chaos_v1:diamond_termite` hook and the same origin tracking. No mob receives custom attribute scaling.

Artifact evidence is in [M4a Born in Chaos artifact audit](../reports/M4a-bic-artifact-audit.md). The native infected-ore fight-and-shard loop remains separate from scheduled compositions; its real-player test remains outstanding.

## Non-goals

No global Born in Chaos behavior changes, custom mob classes, edits to BiC AI/loot, explosive or wall-climbing mobs, M4b kill reward grants, or a claim that solo/group difficulty is balanced before M8 playtests. The mine remains bounded by the configured topology and build height.

## Verification contract

- Parser tests prove all three depth tables have bounded, weighted compositions with written counterplay and reject invalid/cap-breaking profiles.
- Dedicated-server tests cover available/missing entity IDs, supported spawn sites, safe-zone rejection, caps, deterministic site claims, run persistence, and cleanup.
- The opt-in exact-pack runtime audit proves missing entity IDs fail closed, supported nodes spawn once, unsupported surfaces skip cleanly, regional/global caps hold, and both native infected-ore callbacks receive tracking and reset cleanup.
- Exact target-pack startup and the opt-in callback audit prove registry availability, both native infected-ore callbacks, mine-origin tracking, and reset cleanup. The exact-pack M0 loot smoke separately verifies the native shard drop.
- M8 records actual player reaction windows and compares solo/group outcomes at deepest depth before any balance claim; connected-player mining/fighting/collection is not represented by the synthetic callback audit.
