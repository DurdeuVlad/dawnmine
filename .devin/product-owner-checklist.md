# Product-owner delivery checklist

## Contract

- **Goal:** Deliver a verified standalone Rustic Craft 2 public-mine mod that players choose to replay: learnable upper mine, meaningful mining/PvE/exploration contributions, durable equipment progression across morning resets, new deeper counters, team-coordinated endgame, private banked items, safe operation, and friendly permission-aware admin/player communication.
- **System:** Product owner (Codex) maintains the gameplay contract, issues one bounded milestone at a time to Devin through Flux/SWE sessions, checks objective evidence, gets independent review, and uses the authorized merge gates before continuing to the next dependency.
- **Constraints:** Keep one shared mine; use procedural block geometry with no prefab building dependency; account for every pack ore; quartz and contained lava may appear in Chaos Depths; Netherrack off by default and only operator-enabled at bounded ultra-rare extreme depth; do not alter faction mine or global Born in Chaos behavior; preserve player gear/materials/Dispatch through reset; no custom v1 death penalty; never deploy to production without authorization.
- **Evaluation:** Actual target pack artifacts, reproducible build and dedicated-server evidence, relevant multiplayer behavior, exact persistent-state checks, permission/audience tests, performance/economy observations, independent Flux review, and a complete Flux evaluation for each merged milestone.

## Checklist

| Item | State | Evidence required |
|---|---|---|
| Keep the canonical M0–M8 roadmap aligned with all 13 issue contracts | in progress | PR #14 merged as `4c40ad4`; the roadmap has now been reconciled locally to include M1 onboarding (#12), M6 gear progression (#13), M7 reset (#10), and M8 playtests (#11); this reconciliation is not yet merged remotely |
| M0 foundation and compatibility audit | active | Java 21 clean build; target-pack dedicated-server load; exact pack/BiC/ore/recipe report; independent review/evaluation. Runtime/tag and static recipe/equipment candidate reports are independently reviewed; 29 loot tables were evaluated in-pack, but player-break hooks, full ore reconciliation, item stats, and custom/malformed recipes remain open. |
| M1 shared lifecycle and operator onboarding | pending | Shared run/restart/ownership tests; fresh-admin command walkthrough; permission matrix; targeted-message proof |
| M2 deterministic procedural exploration | pending | Seed corpus connectivity/traversability/safety proof; multiplayer shared layout; bounded frontier evidence; no prefab dependency |
| M3 all-ore depth ecology and optional Netherrack setting | pending | Complete pack audit reconciliation; tier generation; native drops; default-zero Netherrack and strict enabled bounds |
| M4 skill-based BiC encounters and mine-only rewards | pending | Verified entities; readable counterplay; bounded cleanup/rewards; outside-mine invariance; solo/group encounter evidence |
| M5 checkpoints, extraction, and private Dispatch | pending | Multiplayer safe travel; transactional bank/storage privacy; persistence/recovery; clear continue-or-bank feedback |
| M6 durable equipment and progression economy | pending | Pack-grounded progression graph; ore/reward source balance; sequential-run upgrade playtest; new deeper counters; reset persistence |
| M7 idempotent morning reset | pending | Time-boundary/downtime/retry tests; relevant warnings; safe ejection; exact gear/material/locker persistence; faction-mine invariance |
| M8 repeat-playtest balance and admin readiness | pending | Multi-day solo/group playtest cards and feedback; measurable team advantage; operator onboarding/recovery; performance/economy/regression reports |
| Deliver all issue merges; no production deployment | pending | Acceptance and verification passed, fresh independent Flux review, Flux-evaluate complete, PR merged; deployment separately authorized |

## Current evidence

- Repo: private `DurdeuVlad/rusticcraft-public-mines`, default branch `main`.
- The M0 implementation and compatibility evidence are delivered in open PR [#15](https://github.com/DurdeuVlad/rusticcraft-public-mines/pull/15) (`m0-foundation` branch) for independent review; the branch passed clean-build, exact-pack dedicated-server, reproducible-report, and independent adversarial-review checks in-session. Merge remains gated on the authorized criteria.
- Product roadmap includes a distinct M6 progression economy and M1 admin onboarding issue. Static pack recipes provide iron-to-steel and steel/silver weapon hooks. Runtime loot testing shows Born in Chaos infected ores yield no direct items; pinned bytecode calls a Diamond Termite encounter and the termite shard has a nine-to-one diamond recipe. Actual player mining/combat/crafting, item stats, and exact upgrade pacing remain unverified.
- Default morning reset is 06:00 in configured server timezone. Netherrack defaults off.

Update only checklist states when evidence changes. A running session or a written acceptance criterion is not proof of implemented behavior.
