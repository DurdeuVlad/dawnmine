# M5a Issue Draft — Shared checkpoints and safe travel

## Planning metadata
- Issue type: Feature / player travel
- Priority: High
- Milestone: M5 — Checkpoints, teleport travel, and Foreman's Dispatch
- Assignee: Unassigned
- Target date: Not set

## Intent

Let players explore a large shared mine, leave for the surface, and re-enter at stations the community has reached without requiring players to walk the full route every session.

## Expectation

The surface entrance and generated checkpoints provide safe teleport actions. Any checkpoint already generated in the current shared run is available to every player. On a new daily run, the previous run's checkpoint stations and destinations are unavailable until regenerated.

## Context that code cannot infer

- The user explicitly chose a shared public mine and asked for teleporting inside/outside and useful checkpoints.
- Planning default: checkpoint availability is shared because the mine and run progress are shared; personal lockers remain private. Record a contrary owner decision before changing this rule.
- A daily reset evacuates everyone before mine cleanup.

## Scope

- Add surface entry and return-to-surface travel through station interactions/commands consistent with M0's server-safe interaction pattern.
- Make generated checkpoint stations accessible to all players once present in the shared run.
- Provide a clear checkpoint status and safe arrival destination.
- Reject teleport requests during initialization/reset and recover players whose destination becomes invalid.

## Non-goals

No arbitrary coordinate teleports, private mine instances, checkpoint respawn/death policy, or inventory changes.

## Dependencies and open decisions

- Depends on M1 safe entry/exit contracts and M2 checkpoint locations.
- M6 uses this ejection path.
- Owner: server owner. Implementer: Devin. Reviewer: independent.

## Acceptance criteria

1. Players can enter from the surface and return from each currently generated checkpoint.
2. Every online player can use the same generated checkpoints in the active run.
3. Teleports land on collision-safe, non-liquid, non-hazardous pads with a safe fallback if the target chunk is unavailable.
4. Reset/initialization blocks travel into invalid state and produces clear player feedback.
5. Old run checkpoints cannot be selected after reseed.
6. Multiplayer tests cover simultaneous entry, exits, and checkpoint use by players who did not discover the station first.

## Verification

Test arrival and departure at every station class on a dedicated server with multiple players; test unloaded chunks, station removal, server restart, and reset transitions.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
