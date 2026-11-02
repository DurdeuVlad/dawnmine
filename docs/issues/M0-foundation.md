# M0 Issue Draft — Standalone mod foundation and pack compatibility

## Planning metadata

- Issue type: Foundation / compatibility implementation
- Priority: Highest; all feature work depends on this result
- Milestone: M0 — Standalone mod foundation and pack compatibility
- Assignee: Devin via the local kickoff session; GitHub assignee unassigned unless a verified Devin account is available
- Target date: Not set

## Intent

Create the standalone mod project that will own the shared public mine and prove its baseline compatibility with the actual Rustic Craft 2 server pack. The server currently has only scripts/prototypes for adjacent features; a dedicated source project is needed before mine code can be safely implemented.

## Expectation

A Java 21 / NeoForge 21.1.248 mod builds and loads on a dedicated server running alongside Rustic Craft 2 2.2.2. A reproducible compatibility report identifies the exact Born in Chaos 1.7.6 entity registry IDs and produces a bounded, evidence-backed candidate inventory of pack ores/resources, including untagged and unusually named blocks. The report states what its discovery methods cannot prove. M0 supplies this audit baseline; M3 owns the complete all-ore registry/drop-source reconciliation and runtime mining proof required before the player-facing all-ores promise is considered delivered. M0 includes technical evidence, not mine gameplay.

## Context that code cannot infer

- Product: shared Crystal Hollows-style public mine; all players share the daily run and seed.
- Runtime target: Minecraft 1.21.1, NeoForge 21.1.248, Java 21, Rustic Craft 2 pack 2.2.2.
- The pack catalog lists Born in Chaos 1.7.6 and GeckoLib 4.9.2; the exact deployed jars and IDs must be verified.
- The user approved generated block geometry and procedural landmarks, no predefined buildings.
- Every pack ore must be included across depth tiers; quartz belongs in the Chaos Depths. Netherrack is explicitly excluded from default generation and is only a future operator-enabled, ultra-rare extreme-depth option.
- M0's ore/resource scan is a bounded discovery baseline. Issue #5 (M3) is the completion gate for reconciling every exact-pack registered block and data/code-defined drop path, assigning every ore-bearing block, and verifying native player mining behavior. Do not describe M0's candidate list as proof that the all-ores player promise is already satisfied.
- Daily map/run state resets, but equipment and progression materials must persist; audit real pack recipes/items/equipment so later milestones can create a truthful upgrade path.
- For now, Netherrack does not generate. A later operator setting may enable an ultra-rare extreme-depth option; default must remain off.
- Existing KubeJS faction mines are explicitly out of scope and must not be edited.
- This milestone is the first Devin implementation and test task. Use Flux routing and close the bounded issue with proof.

## Scope

- Create the Gradle/NeoForge project scaffold and mod metadata.
- Choose and verify a unique mod ID/package, recording it in Decision.md.
- Use a server-safe mod entry point and prove no client-only class loads are required.
- Build the mod and load it on a dedicated NeoForge server with the target pack.
- Inspect the exact BiC artifact and produce a compatibility report of candidate entity IDs with source evidence.
- Produce a reproducible candidate inventory from runtime common tags and pack artifact inspection; identify untagged/special-case candidates and preserve exact artifacts and coverage limits for M3's exhaustive reconciliation.
- Audit relevant actual pack items, equipment tiers, crafting recipes, and mine materials/sinks to seed the later durable progression graph; include artifact/version/hash evidence.
- Document exact tool versions, setup, commands, output, and remaining unknowns.

## Non-goals

- No mine world, generation algorithms, custom dimension, ore placement, entity spawning, checkpoint, storage, reset, faction-mine modification, or economy tuning.
- Do not add custom loot or alter Born in Chaos behavior.
- Production release and server deployment are out of scope. Commit, push, and merge are authorized for this issue only after all acceptance criteria, build/dedicated-server checks, independent Flux review, and flux-evaluate pass.

## Dependencies and open decisions

- None for project setup.
- Actual exact BiC and modpack jars may be missing locally; use the pack's pinned artifacts only and report retrieval gaps.
- Reset schedule default is 06:00 in configured server timezone; mine bounds, ore distribution, and locker capacity remain later decisions and do not block M0.
- Product owner: Codex. Implementer: Devin. Keep the issue unassigned in GitHub if no verified Devin GitHub account is available; start via the local Devin CLI prompt.

## Acceptance criteria

1. A clean Java 21 Gradle build succeeds against NeoForge 21.1.248 and Minecraft 1.21.1.
2. A dedicated server starts with the new mod and the Rustic Craft 2 pack without client-only class errors or dependency conflicts.
3. The compatibility report records the exact BiC jar/version and registry IDs for candidate hostile mobs, separating verified facts from candidates needing playtesting.
4. The report compares registered blocks against runtime common ore tags and the documented M0 discovery passes, lists every candidate found (including untagged/special-case candidates), and records reproducible sources and coverage limits. It makes no claim of final all-ore completeness; M3 issue #5 must resolve all registered-block candidates and code/player-break drop paths before the all-ores promise is accepted.
5. A separate progression appendix inventories real pack recipes/items/equipment relevant to mining, combat, protection, hazards, and crafting, distinguishes verified from unknown, and records exact artifacts.
6. No source or configuration in the existing faction mine is changed.
7. The issue handoff includes commands and actual outputs for build/server checks, a fresh independent Flux review, and a Flux evaluation verdict.

## Verification

- Run Java/Gradle version checks and a clean build.
- Run the generated dedicated-server configuration with the target modpack/dependencies; inspect startup log for mod load, dependency errors, and side violations.
- Inspect the pinned BiC jar's mod metadata and registries to verify exact entity IDs; record jar hash/version.
- Emit or inspect a runtime dump of the block ore tags and reconcile it with the actual installed mod catalog; verify the bounded candidate inventory and its explicitly stated discovery limits. The exhaustive ore-bearing-block and player-break proof is verified under M3 issue #5.
- Review git diff and ensure no files under the KubeJS faction-mine system changed.

## PR contract

Every implementation PR must repeat intent, expectation, acceptance criteria and evidence, non-code context, scope/non-goals, affected owner boundary, dependencies, test commands/results, and residual risks. A linked issue alone is insufficient.



## Background and research

- The local target manifest at C:\Users\Vlad\Documents\Github\rustic-craft-2-modpack\modpack.json pins Rustic Craft 2 2.2.2, Minecraft 1.21.1, NeoForge 21.1.248.
- The local mod catalog lists Born in Chaos 1.7.6 and GeckoLib 4.9.2. Verify the exact deployed artifacts before integration.
- NeoForge setup and Java 21 reference: https://docs.neoforged.net/docs/1.21.1/gettingstarted/
- NeoForge tag reference: https://docs.neoforged.net/docs/1.21.1/resources/server/tags/
- Born in Chaos versions and loader support: https://modrinth.com/mod/borninchaos/versions

