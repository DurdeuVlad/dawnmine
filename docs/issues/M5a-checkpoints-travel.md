# M5a Issue Draft — Shared checkpoints and safe travel

## Planning metadata
- Issue type: Feature / player travel
- Priority: High
- Milestone: M5 — Checkpoints, teleport travel, and Foreman's Dispatch
- Assignee: Unassigned
- Target date: Not set

## Intent

Let players explore a large shared mine, leave for the surface, and re-enter at stations the community has reached while making a clear decision to bank or risk pushing farther.

## Expectation

The surface entrance and generated checkpoints provide safe teleport and bank access. The associated Public Mines surface hub/entry-return pads, generated checkpoint pads, and Dispatch interaction pads are explicit safe zones: hostile mobs cannot spawn, enter, or remain there, and players cannot attack mobs from inside. Each safe-zone volume is registered with the active run without assuming an arbitrary radius. Any checkpoint already generated in the current shared run is available to every player. Checkpoint feedback shows current depth, next route/risk where known, and the option to bank or push. On a new daily run, previous stations and safe-zone registrations expire until regenerated.

## Context that code cannot infer

- The user explicitly chose a shared public mine and asked for teleporting inside/outside and useful checkpoints.
- The user specified no PvP anywhere in the mine and no PvE combat in safe zones.
- Product decision: checkpoint availability is shared because the mine and run progress are shared; personal lockers remain private. Record evidence in Decision.md before changing this rule.
- Safe zones are bounded volumes tied to mine-associated stations, not guessed-radius regions. They must remain hostile-mob-free, apply only to those explicit areas, and must not disable mobs globally or alter the factions mine.
- A daily reset evacuates everyone before mine cleanup.
- Daily map/run state resets, but useful carried equipment/materials and private Dispatch contents persist. Checkpoints and banking should make extraction understandable, not impose an undocumented penalty.
- The first version preserves vanilla death/inventory behavior; no extra mod-specific death penalty.

## Scope

- Add surface entry and return-to-surface travel through station interactions/commands consistent with M0's server-safe interaction pattern.
- Make generated checkpoint stations accessible to all players once present in the shared run.
- Register explicit server-side safe-zone volumes for the Public Mines surface entry/return area, every generated checkpoint pad, and Dispatch interaction pads. Keep zones limited to those station areas.
- Register the station volumes through M2c (#23)'s generic run-scoped zone registry; apply its harm/effect protection and M4a (#6)'s hostile-mob exclusions to them.
- Provide a clear checkpoint status and safe arrival destination.
- Show relevant nearby route/depth and bank/extract feedback before players commit to travel or push farther.
- Reject teleport requests during initialization/reset and recover players whose destination becomes invalid.
- Clear stale safe-zone registrations when checkpoints expire, the run resets, or station recovery removes a pad.

## Non-goals

No arbitrary coordinate teleports, private mine instances, checkpoint respawn/death policy, inventory changes, PvE immunity outside explicitly registered Public Mines safe zones, or global mob-rule changes.

## Dependencies and open decisions

- Depends on M1 safe entry/exit contracts, M2 checkpoint locations, M2c issue #23 safe-zone harm/effect protection, and M4a issue #6 hostile-mob exclusion.
- M4a (#6) must consume the registered zone volumes and keep its hostile encounters outside them.
- M7 uses this ejection path and clears old run stations/zones.
- Product owner: Codex. Implementer: Devin. Reviewer: independent.

## Acceptance criteria

1. Players can enter from the surface and return from each currently generated checkpoint.
2. Every online player can use the same generated checkpoints in the active run.
3. Teleports land on collision-safe, non-liquid, non-hazardous pads with a safe fallback if the target chunk is unavailable.
4. Reset/initialization blocks travel into invalid state and produces clear player feedback.
5. Old run checkpoints and safe-zone registrations cannot be selected after reseed.
6. Each designated station has an explicit safe-zone volume registered to the active mine; zone boundaries do not extend beyond the intended station area or alter other worlds/faction mine.
7. Safe-zone checks show no hostile mobs spawn, enter, or remain in the zone; hostile damage/effects cannot reach players there, and players inside cannot harm mobs outside it.
8. Multiplayer tests cover simultaneous entry, exits, checkpoint use by players who did not discover the station first, and safe-zone boundaries. Safe zones remain hostile-mob-free; normal PvE resumes outside them; mine-wide PvP and harmful player effects remain disabled.

## Verification

Test arrival and departure at every station class on a dedicated server with multiple players; test unloaded chunks, station removal, server restart, safe-zone boundary interactions, and reset transitions.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
