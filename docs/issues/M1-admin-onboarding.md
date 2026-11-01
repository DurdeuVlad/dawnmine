# M1 Issue Draft — In-game admin onboarding and safe control

## Planning metadata

- Issue type: Operator experience / configuration
- Priority: High; safe operation is a prerequisite to public gameplay
- Milestone: M1 — Shared run lifecycle and operator onboarding
- Assignee: Devin session; no GitHub account assignment without verification
- Target date: Not set

## Intent

Let a new server admin set up, inspect, validate, and safely configure Public Mines in game without reading source code. Keep operational details private to authorized staff and explain player actions only to players who need them.

## Expectation

`/publicmine help` explains the command tree and first setup path. Permission-gated admin commands expose setup/validation, status, config inspection/change, and documented recovery. Each result confirms what actually happened, gives a safe next step, and uses friendly plain-language messages. Player-facing notices are contextual and sent only to the relevant players.

## Context that code cannot infer

- This is a server-side NeoForge mod for the Rustic Craft 2 2.2.2 target; client installation cannot be required for admin commands/help.
- The owner wants commands to modify settings and set up/onboard admins, with communication shown only to the right players.
- Netherrack is disabled by default. If enabled, the hard bound is one placement attempt per 256 eligible 16×16 chunk columns at deepest tier, one cluster up to three blocks; operators may only increase the interval through 65,536.
- Reset starts at 06:00 in the configured server timezone by default. Show effective schedule and warnings to mine occupants.
- See `Progression.md` for the full player and operator experience contract.

## Scope

- Register `/publicmine` with `help`, `status`, `setup validate`, `setup initialize` (idempotent), `config get`, `config set`, and documented safe `recover` operations. Only include operations supported by the lifecycle implementation; unknown/unavailable commands must be clearly reported.
- Define an explicit permission matrix for regular players, trusted/moderator staff, admins/operators, and server console. Check permission server-side for each mutating action; default mutations to the server's operator threshold and never rely on hidden client state.
- Persist valid config changes to the authoritative server config. Reject unknown keys, invalid values, and values above hard bounds with the valid range and a corrective example. Runtime changes that require reseeding/restart must say so and only apply at the safe transition.
- Keep read-only player help/entry guidance separate from admin diagnostics, world coordinates, reset internals, and recovery details.
- On setup, inspect dependencies/state first, create only mine-owned resources, and report every result. Re-running setup cannot duplicate the dimension/region, structures, command state, or owned-area records.
- Add localized/friendly messages for enter, exit, checkpoint, bank, reset notice, validation failure, and unavailable service. Send only to the invoking actor, affected mine players, or admins as appropriate; no routine global broadcast.
- Add command usage/help text, config comments/documentation, and an operator quick-start/recovery section.

## Non-goals

- No general server permissions framework or client GUI requirement.
- No unsafe arbitrary-world editing, mass teleport, or unconfirmed destructive command.
- No public broadcast of admin errors, stack traces, player inventory contents, or staff-only status.
- No ability for config to exceed generation, spawn, ore-rate, Netherrack, or reset safety limits.

## Dependencies and open decisions

- Depends on M0 server entry point and M1 shared-run state/ownership.
- Verify permission checks, command APIs, config scope/persistence, and reload behavior against pinned NeoForge 21.1.248; design the command UX before implementation.
- Exact permission level can be config-controlled only if bounded and defaulted safely; otherwise use the documented server operator threshold.
- Owner: product owner. Implementer: Devin. Independent Flux reviewer required.

## Acceptance criteria

1. `/publicmine help` is available on a dedicated server and gives an actionable first-run setup path, command syntax, permission notes, and recovery entry point.
2. An admin with a fresh server can initialize and validate a mine, see readiness blockers and exact next steps, inspect config, safely change a supported setting, and verify that it persisted without editing source code.
3. Setup and recovery are idempotent, mine-owned, auditable, and safe after restart or partial failure; no faction-mine or unrelated-world state changes.
4. A test matrix proves unauthorized player/staff cannot invoke admin mutations; authorized operator/admin and console behavior is explicit and verified. Read-only player entry/help exposes no private admin diagnostics.
5. Invalid/unknown values and unsafe Netherrack rates are rejected with a friendly corrective response; default config creates no Netherrack; the enable path cannot exceed ultra-rare extreme-depth constraints.
6. Reset and mine messages go only to affected players; admin diagnostics go only to authorized admin/console and logs. No global routine spam or sensitive player data leaks.
7. Tests cover each permission class, setup repeat, config validation/persistence, message audience, partial failure, and restart.

## Verification

Use a dedicated server and accounts/command sources representing each permission level. Capture exact command inputs/outputs, server config before/after, persisted mine state, and logs. Verify the command tree via in-game help and tab completion. Test with the target pack and prove faction-mine fixtures are unchanged.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
