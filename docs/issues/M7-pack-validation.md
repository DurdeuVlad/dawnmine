# M7 Issue Draft — Pack playthrough and release readiness

## Planning metadata
- Issue type: Integration validation and operations readiness
- Priority: High before deployment
- Milestone: M7 — Pack-level playthrough, balance, and operations readiness
- Assignee: Unassigned
- Target date: Not set

## Intent

Prove the complete public-mine loop works on the real server pack under multiplayer/restart/reset conditions and give operators enough information to run and recover it.

## Expectation

A dedicated-server playthrough covers shared exploration, all pack ore tiers, Nether Chaos hazards, Born in Chaos encounters/rewards, checkpoints/travel, private Dispatch storage, restart recovery, and the morning reset. Performance, resource rates, and mob pressure are measured and reviewed. Setup, configuration, status, and recovery are documented.

## Context that code cannot infer

- Target is Rustic Craft 2 2.2.2, Minecraft 1.21.1, NeoForge 21.1.248.
- All prior milestones require evidence before this one.
- Do not call the mod ready from compile success alone; the closest safely available dedicated-server environment is required.
- Any release/publication or production deployment still requires separate owner authorization.

## Scope

- Run a multi-player pack-level acceptance session across every milestone flow.
- Profile generation and entity pressure; tune only from recorded observations and owner review.
- Document install/configuration, daily reset behavior, compatibility, admin diagnostics, and recovery.
- Record any residual unsupported scenarios or defer release where evidence is insufficient.

## Non-goals

No public mod release, production deployment, license decision, modpack unrelated updates, or faction-mine redesign.

## Dependencies and open decisions

- Depends on completed M0-M6 evidence, owner-confirmed reset schedule, and reviewed ore/mob reward rates.
- Target concurrency and acceptable tick-time budget require owner agreement before release sign-off.
- Owner: server owner. Implementer: Devin. Reviewer: independent.

## Acceptance criteria

1. A multi-player dedicated-server session records all end-to-end journey steps and results.
2. Runtime ore audit matches the pack catalog with no missing tiered ores.
3. Mine and non-mine Born in Chaos behavior/reward cases are both checked.
4. Checkpoint transport and personal Dispatch storage pass privacy, restart, full-capacity, and reset tests.
5. Daily reset passes online, offline, downtime, and interrupted-cleanup tests.
6. Generation/tick/entity metrics are recorded against owner-approved concurrency/performance targets.
7. Operator docs explain configuration, status, and recovery; all open release blockers are explicit.
8. A faction-mine regression check confirms its existing claim/access/progression/reset flow remains unchanged.
9. Independent Flux review and Flux evaluation support the final release-readiness verdict.

## Verification

Use the real target pack on a dedicated server with multiple players. Capture version manifest, test commands/log references, before/after storage snapshots, reset date/run IDs, performance observations, and faction-mine regression evidence.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
