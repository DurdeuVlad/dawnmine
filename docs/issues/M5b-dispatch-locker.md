# M5b Issue Draft — Personal Foreman's Dispatch locker

## Planning metadata
- Issue type: Feature / persistent player storage
- Priority: High
- Milestone: M5 — Checkpoints, teleport travel, and Foreman's Dispatch
- Assignee: Unassigned
- Target date: Not set

## Intent

Give players a themed way to bank expedition loot at a checkpoint and find it at the surface, with more capacity than a standard Ender Chest.

## Expectation

A personal Dispatch locker is available at each generated checkpoint and the surface depot. The same player's inventory is visible at every terminal; another player cannot see or take it. It supports deposit and retrieval, handles full inventories safely, and survives disconnect, server restart, and daily mine reset.

## Context that code cannot infer

- User-approved behavior: checkpoints are inventory deposit stops and contents are retrievable outside.
- The storage is private to each player and persistent across daily mine resets.
- Capacity must exceed 27 slots; proposed minimum is 54 slots. Final capacity/expansion is owner-reviewable before release.
- This locker is a separate mine feature, not a change to vanilla Ender Chest contents.

## Scope

- Implement server-authoritative per-player persistence and a terminal interaction at checkpoints and surface.
- Provide at least 54 item slots, deposit and retrieve actions, and clear feedback for no space/invalid item states.
- Make item movement transactional so failed UI/teleport/disconnect operations do not duplicate or delete items.
- Keep personal data keyed by stable player UUID and resilient across run changes.

## Non-goals

No shared team vault, trading, paid slots, auto-sorting/filters, item loss on death, or reset of personal storage.

## Dependencies and open decisions

- Depends on M1 player-data persistence and M5a terminal locations.
- Owner may change capacity before implementation; avoid an irreversible fixed storage schema.
- Owner: server owner. Implementer: Devin. Reviewer: independent.

## Acceptance criteria

1. Each player has a private locker with at least 54 slots.
2. Checkpoint and surface terminals show the same contents for that player.
3. A second player cannot read or mutate the first player's contents.
4. Deposit/retrieval respects inventory capacity and causes no item duplication/loss across full inventory, logout, server stop/start, or UI close.
5. Contents persist across daily mine reseed and never count as mine-owned terrain or run state.
6. Tests cover UUID ownership, persistence migration, full inventory, reconnect, and simultaneous access.

## Verification

Use two players to verify isolation and run deposit/withdraw scenarios at a checkpoint and surface depot; restart the dedicated server and trigger a mine reset before confirming contents remain exact.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
