# M1 Issue Draft — Shared mine run and lifecycle foundation

## Planning metadata
- Issue type: Architecture and implementation
- Priority: High; blocks generated world work
- Milestone: M1 — Shared mine run and safe world lifecycle
- Assignee: Unassigned
- Target date: Not set

## Intent

Create one authoritative server-side run for every player. All later generation and reset work depends on a saved seed and an exact ownership boundary so the daily cleanup cannot damage unrelated world or faction-mine state.

## Expectation

The mod persists a single active run with a run ID, seed, day identity, generated frontier, region/bounds ownership, and lifecycle status. Restart recovery returns to that same shared run without duplicating owned state. The chosen world placement strategy is justified with a disposable dedicated-server prototype and documented before production generation begins.

## Context that code cannot infer

- Accepted experience and environment are in Business.md and Decision.md.
- Target: Rustic Craft 2 2.2.2, Minecraft 1.21.1, NeoForge 21.1.248, Java 21.
- All players share one active mine. The factions mine must remain unchanged.
- Exact morning reset time is deferred to M6; this issue defines state needed for a safe reset, not the schedule.

## Scope

- Compare custom dimension and isolated-region options against actual server APIs and operational constraints.
- Implement persisted run identity, seed, generation frontier, depth/region index, mine-owned area tracking, and safe lifecycle transitions.
- Establish safe surface and mine arrival coordinates, entry lock/ready states, and recovery behavior for partial initialization.
- Document chosen ownership and boundary model in Decision.md and an architecture note.

## Non-goals

No rich map geometry, ore placement, mobs, rewards, checkpoint GUI, Dispatch locker, or scheduled reset. Do not edit the existing faction mine.

## Dependencies and open decisions

- Depends on M0 build, dedicated-server, and pack audit evidence.
- Technical choice of dimension versus bounded area must follow prototype results; do not select it before evidence.
- Generation bounds and per-tick budget must be recorded before M2.
- Owner: server owner. Implementer: Devin. Reviewer: independent.

## Acceptance criteria

1. Every online player resolves to the same active run ID and seed.
2. Run state survives a normal stop/start with no duplicate initialization.
3. A failed/partial initialization can be detected and safely retried or reported without clearing non-mine blocks.
4. Cleanup ownership is explicit and queryable for every region/volume allocated to the mine.
5. Entry is unavailable while the world is uninitialized/resetting and points only to safe positions when ready.
6. The decision record names evidence, tradeoffs, bounds, and recovery behavior.
7. No faction-mine script/config is modified.

## Verification

Use a dedicated server with two player sessions or equivalent server-side player fixtures; compare state and entry destinations. Restart mid-run and during a simulated partial initialization. Inspect saved state and owned-area records. Confirm only the test mine area is subject to cleanup.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
