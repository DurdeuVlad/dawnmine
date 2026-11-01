# M6 Issue Draft — Daily morning mine reset

## Planning metadata
- Issue type: Feature / scheduled lifecycle
- Priority: High
- Milestone: M6 — Configurable morning cycle and complete mine reset
- Assignee: Unassigned
- Target date: Not set

## Intent

Keep the shared public mine fresh and fair while safely evacuating players and preserving the personal items they banked or carry.

## Expectation

At one configured morning time each server-local day, the server warns players, disables new entry, moves active mine players to the safe surface, clears only mine-owned terrain/entities/loose drops, and creates one new shared run with a new seed. Player inventories and Dispatch lockers are preserved. A downtime crossing the reset time results in exactly one overdue reset on startup.

## Context that code cannot infer

- The owner requires a daily morning reset and teleporting anyone still inside outside.
- Preserve carried inventory and Dispatch locker contents.
- Proposed default: 06:00 local server time; owner must confirm exact hour/timezone before release.
- Reset must never affect the factions mine or other dimensions/regions.

## Scope

- Add configurable reset time/timezone and persist last completed run date/ID.
- Add configurable advance warnings, entry lock, player evacuation, cleanup transaction, new seed/run creation, and recoverable status.
- Handle restart before, during, and after scheduled reset; make execution idempotent.
- Add admin-facing status/recovery action only where necessary to diagnose a failed reset.

## Non-goals

No inventory wipe, Dispatch wipe, faction-mine reset, daily quests, economy rewards for resets, or forced server restart.

## Dependencies and open decisions

- Depends on M1 owned-area/lifecycle state, M2 generation, and M5 travel/storage.
- Release is blocked until owner confirms the reset clock/timezone and the configured value is tested.
- Owner: server owner. Implementer: Devin. Reviewer: independent.

## Acceptance criteria

1. At the configured time, warnings occur at configured intervals and new mine entry is blocked during reset.
2. Every player in the mine is moved to a safe surface position before cleanup.
3. Exactly one new run ID/seed is created for each scheduled date, even across downtime or restart retries.
4. All mine-owned generated blocks, mobs, and loose mine drops are cleared; no block outside owned bounds is changed.
5. Player inventories, private Dispatch contents, and factions-mine state remain unchanged.
6. Interrupted cleanup is detected, persisted, and safely retried without wiping unrelated areas or duplicating rewards.
7. Tests cover just-before/at/after time, restart during reset, downtime crossing reset, multiple players inside, and retry failure.

## Verification

Run a controlled schedule in a disposable dedicated server, inspect owned-area diffs and player storage snapshots, stop/start during each reset phase, and verify one reset per date. Confirm the factions mine remains unchanged.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
