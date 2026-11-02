# M4a Born in Chaos artifact audit

## Artifact identity

- Pack jar: `born_in_chaos_[Neoforge]_1.21.1_1.7.6.jar`
- Path used for inspection: `C:\m0s-audit\mods\born_in_chaos_[Neoforge]_1.21.1_1.7.6.jar`
- SHA-256: `A0271A24DB8622434D9573C5E789D451A05C9211173885049284E1911E2499C7`
- This exactly matches the artifact recorded in the M0 native loot report.

## Chosen candidates

| Registry ID | Artifact evidence | Intended role and counterplay |
| --- | --- | --- |
| `born_in_chaos_v1:decrepit_skeleton` | Registered entity; class extends `Monster`, uses a melee attack goal, ground spawn placement, and audited attributes of 15 HP, 3 attack damage, 0 armor, and 0.23 movement speed. | A readable pursuer that pressures players who stop to mine. Move through a clear retreat lane and use corners to break pursuit. |
| `born_in_chaos_v1:bone_imp` | Registered entity; class extends `Monster`, uses a melee attack goal, ground spawn placement, and audited attributes of 20 HP, 3 attack damage, 3.5 armor, fire immunity, and 0.31 movement speed. | A faster, more durable melee threat that contests space. Keep moving, focus targets, and regroup before re-engaging. |
| `born_in_chaos_v1:diamond_termite` | Exact registry ID and native infected-diamond-ore summon procedure verified in M0; the shard loot and nine-shards-to-diamond recipe were also observed. | Retain the ore's native ambush/material loop; capture these mobs in mine-origin tracking and reset cleanup. Do not duplicate-summon them in the scheduled table. |

These are artifact facts and encounter design hypotheses, not balance or counterplay playtest results. M8 still owns solo-versus-group balance acceptance.

## Exclusions

- `baby_spider` and `mother_spider`: spider inheritance/wall-climbing makes safe-zone containment and tunnel play more complex.
- `zombie_lumberjack`: artifact class includes `BreakDoorGoal`, unsafe near mine boundaries.
- `phantom_creeper`: explosive behavior is excluded until containment and counterplay are directly tested.
- `skeleton_demoman`: custom ranged projectile behavior is not selected until its hazards are tested in confined routes.
- `decaying_zombie`: artifact class includes `BreakDoorGoal`.
- `maggot`: possible weak swarm role, but hitbox and in-game availability were not validated.

## Limits

The jar audit establishes registry IDs and class/goal/attribute facts. It does not establish fair attack windows, actual spawned behavior in the generated mine, nor player-break encounter experience. Exact-pack startup and focused playtests remain required.
