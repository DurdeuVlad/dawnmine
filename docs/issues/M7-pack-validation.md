# M8 Issue Draft — Pack playthrough and release readiness

## Planning metadata
- Issue type: Integration validation and operations readiness
- Priority: High before deployment
- Milestone: M8 — Pack-level playthrough, balance, and operations readiness
- Assignee: Unassigned
- Target date: Not set

## Intent

Prove the complete public-mine loop delivers learnable early depth, satisfying repeat-run gear progress, changing late-depth tactics, genuine team coordination, and safe admin operation on the real server pack.

## Expectation

A set of repeatable solo/group dedicated-server playtests covers shared exploration, all pack ore tiers, quartz/lava Chaos hazards, default/optional Netherrack behavior, Born in Chaos encounters/rewards, checkpoints/banking, real gear upgrades, restart recovery, and the morning reset. Reports capture routes, mining/PvE contributions, bank-or-push decisions, upgrade effects, deeper counters, audience-correct messaging, team-vs-solo outcomes, and player feedback. Performance/economy are measured. New operators can set up/status/configure/validate/recover using in-game help and commands.

## Context that code cannot infer

- Target is Rustic Craft 2 2.2.2, Minecraft 1.21.1, NeoForge 21.1.248.
- All prior milestones require evidence before this one.
- Do not call the mod ready from compile success alone; the closest safely available dedicated-server environment is required.
- A claim of fun/addictiveness is not proven by code tests. Record repeat-session choices and short player feedback without overstating subjective outcomes.
- Team-required depth needs a recorded solo-versus-coordinated-group comparison at the same gear/depth encounter.
- The product owner has authorized a tester-focused GitHub prerelease after all milestones pass; this does not authorize production-server deployment.

## Scope

- Run a multi-player pack-level acceptance session across every milestone flow.
- Profile generation and entity pressure; tune only from recorded observations and documented product-owner decisions.
- Run at least two successive daily runs to prove durable gear/material progression and fresh run state.
- Document install/configuration, daily reset behavior, permission matrix, in-game onboarding, targeted messaging, admin diagnostics, and recovery.
- Record any residual unsupported scenarios or defer release where evidence is insufficient. Publish a tester-focused GitHub prerelease containing the verified target-compatible JAR, SHA-256 checksum, changelog, tester install/run instructions, and known limitations.

## Non-goals

No production-server deployment, stable release, license decision, modpack unrelated updates, or faction-mine redesign.

## Dependencies and open decisions

- Depends on completed M0-M7 and M2c evidence, the configured 06:00 server-timezone reset default, and reviewed ore/mob reward rates.
- Target concurrency and acceptable tick-time budget must be recorded in the M8 evidence plan before release sign-off.
- Product owner: Codex. Implementer: Devin. Reviewer: independent.

## Acceptance criteria

1. A multi-player dedicated-server session records all end-to-end journey steps and results.
2. Runtime ore audit matches the pack catalog with no missing tiered ores.
3. Mine and non-mine Born in Chaos behavior/reward cases are both checked.
4. Checkpoint transport and personal Dispatch storage pass privacy, restart, full-capacity, and reset tests.
5. Daily reset passes online, offline, downtime, and interrupted-cleanup tests.
6. Repeat-run playtests demonstrate an upgrade enables a meaningful deeper attempt while later challenges require new tactics.
7. Mining, PvE, and exploration all make useful contributions in the tested progression graph.
8. A coordinated group materially outperforms or succeeds where the same deep encounter is not a routine solo clear; conclusion is based on recorded sessions.
9. Default config creates no Netherrack; enabled config proves at most one attempt per 256 deepest-tier chunk columns, at most three blocks in a cluster, and config only allows rarer placement.
10. Generation/tick/entity/economy metrics are recorded against documented concurrency/performance targets.
11. A new operator completes setup/help/status/config/validation/recovery using the documented permission matrix; messages reach only intended audiences.
12. A faction-mine regression check confirms its existing claim/access/progression/reset flow remains unchanged.
13. Independent Flux review and Flux evaluation support the final release-readiness verdict.
14. A tester-focused GitHub prerelease is available only after acceptance gates pass and contains the verified JAR, matching SHA-256 checksum, changelog, clear install/run steps for the target pack, and known limitations.

## Verification

Use the real target pack on a dedicated server with multiple players. Capture version manifest, test commands/log references, before/after storage snapshots, reset date/run IDs, performance observations, and faction-mine regression evidence. Download the release artifact and verify its checksum and tester instructions against the tested build.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
