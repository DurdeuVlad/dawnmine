# M7 Issue Draft — Daily morning mine reset

## Planning metadata
- Issue type: Feature / scheduled lifecycle
- Priority: High
- Milestone: M7 — Configurable morning cycle and complete mine reset
- Assignee: Unassigned
- Target date: Not set

## Intent

Keep the shared public mine fresh and fair while safely evacuating players and preserving the personal items they banked or carry.

## Expectation

At 06:00 in the configured server timezone by default (admin-adjustable), the server warns relevant players, disables new entry, moves active mine players to the safe surface, clears mine-owned terrain/entities/loose drops/run-local checkpoints, and creates one new shared run with a new seed. Carried equipment/progression materials and Dispatch lockers are preserved so players can use capability earned from earlier runs. A downtime crossing the reset time results in exactly one overdue reset on startup.

## Context that code cannot infer

- The owner requires a daily morning reset and teleporting anyone still inside outside.
- Preserve carried inventory and Dispatch locker contents.
- Default: 06:00 in the configured server timezone; command help/status shows the effective time and next reset. Admins can adjust it using validated server config/commands.
- Reset must never affect the factions mine or other dimensions/regions.
- Terrain and run-local state reset; durable player equipment, materials, and private Dispatch contents persist. Do not reset capability or add a custom death penalty.

## Scope

- Add configurable reset time/timezone and persist last completed run date/ID; reject invalid zone/time values with a clear admin message.
- Add configurable advance warnings, entry lock, player evacuation, cleanup transaction, new seed/run creation, and recoverable status.
- Handle restart before, during, and after scheduled reset; make execution idempotent.
- Add admin-facing status/recovery action only where necessary to diagnose a failed reset.

## Non-goals

No inventory wipe, Dispatch wipe, faction-mine reset, daily quests, economy rewards for resets, or forced server restart.

## Dependencies and open decisions

- Depends on M1 owned-area/lifecycle state, M2 generation, and M5 travel/storage.
- Reset default is set to 06:00 in the configured server timezone; test and report effective settings. Product owner: Codex. Implementer: Devin. Reviewer: independent.

## Acceptance criteria

1. At the configured time, warnings occur at configured intervals and new mine entry is blocked during reset.
2. Every player in the mine is moved to a safe surface position before cleanup.
3. Exactly one new run ID/seed is created for each scheduled date, even across downtime or restart retries.
4. All mine-owned generated blocks, mobs, and loose mine drops are cleared; no block outside owned bounds is changed.
5. Player inventories, private Dispatch contents, and factions-mine state remain unchanged.
6. Interrupted cleanup is detected, persisted, and safely retried without wiping unrelated areas or duplicating rewards/material progression.
7. Player warnings target only players in the mine; admins/console receive operational diagnostics separately.
8. Tests cover just-before/at/after time, restart during reset, downtime crossing reset, multiple players inside, retry failure, and exact carried/locker/progression persistence.

## Verification

Run a controlled schedule in a disposable dedicated server, inspect owned-area diffs and player storage snapshots, stop/start during each reset phase, and verify one reset per date. Confirm the factions mine remains unchanged.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
