# M2b Issue Draft — Procedural block generation and frontier expansion

## Planning metadata
- Issue type: Feature / world generation
- Priority: High
- Milestone: M2 — Crystal Hollows-style procedural exploration world
- Assignee: Unassigned
- Target date: Not set

## Intent

Turn a validated shared topology into explorable terrain and extend the world as players approach its frontier without stalling the server or producing disconnected/unreachable content.

## Expectation

The server builds tunnels, caverns, pockets, landmarks, checkpoint alcoves, region materials, and bounded hazards directly from deterministic block algorithms. Generation is persisted per segment and can resume safely after restart. Players entering the frontier trigger a bounded amount of additional generation.

## Context that code cannot infer

- The map is one shared daily run; generated segments and frontier are shared.
- No prefab structures or external building files.
- M1 owns world placement and cleanup bounds. M2a owns topology and deterministic segment plans.
- Chaos Depths use Nether-like palettes, including basalt/netherrack, and must constrain lava inside mine-owned terrain.

## Scope

- Implement topology-to-block generation and segment identity/idempotency.
- Add safe scheduling/chunk checks so a player event/tick does not synchronously generate an unbounded area.
- Persist generated frontier and completed segment IDs.
- Validate player navigation and checkpoint pads in generated space.
- Include generation failure detection and operator-readable diagnostics.

## Non-goals

No ore tables, mob spawns/rewards, storage persistence, final reset clock, custom building templates, or changes to unrelated world generation.

## Dependencies and open decisions

- Depends on M1 area ownership and M2a topology.
- M3 uses the resource-field placements; M4 uses encounter locations; M5 uses checkpoint locations.
- Required chunk/tick budget is measured and recorded; no guessed performance claim.
- Product owner: Codex. Implementer: Devin. Reviewer: independent.

## Acceptance criteria

1. Replaying a segment plan produces the same block geometry and does not duplicate/overwrite a previously completed segment.
2. New segments connect to the existing route and remain inside owned bounds.
3. Frontier expansion processes bounded work, yields across ticks/chunks as needed, and leaves the server responsive at the target pack load.
4. Restart during generation resumes or safely replays without holes, unsafe teleport pads, or damage outside the mine.
5. Liquid hazards remain contained inside the mine area and are removed by mine cleanup.
6. Checkpoint and landmark positions are clear, reachable, and collision-safe.
7. Automated tests or deterministic fixtures cover segment idempotency, bounds, interruption, and connectivity.

## Verification

Use dedicated-server profiling with representative chunks and concurrent frontier arrivals. Restart during queued and in-progress generation. Inspect owned-area diffs and test liquid containment and player pathing.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
