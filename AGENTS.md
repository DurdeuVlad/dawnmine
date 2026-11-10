# Agent instructions

This repository is a collaborative server-mod project. Before work on every issue:

1. Invoke the Flux router with the installed Flux skill, then select only the smallest useful Flux capabilities for the issue.
2. Read the linked milestone and issue contract before editing.
3. For implementation, use flux-discovery when scope or runtime facts are uncertain, flux-research for external API/version facts, flux-plan for non-trivial changes, flux-architecture when ownership or boundaries change, flux-build for code, flux-test and flux-verify for evidence, flux-review for independent adversarial review, and flux-evaluate before claiming completion. Use flux-close-issue when the issue is a bounded implementation and verification unit.
4. Keep work inside the assigned issue. Record assumptions and blockers rather than guessing at material product policy.
5. Prefer a fresh independent reviewer for meaningful implementation work. Include commands and results in the handoff.

## Project constraints

- Target Minecraft 1.21.1, NeoForge 21.1.248, and Java 21 unless the owner changes the target.
- Keep gameplay server-side. Do not introduce client-only class loads on a dedicated server.
- The mine is shared by all players in one daily run, randomized from one shared run seed, and must not interfere with the factions mine.
- Generate the environment from code and block layouts; the product does not depend on prefab structures or authored building files. Exception (owner-approved, 2026-11-10): the spawn/hub area only may be authored in-game and saved as a vanilla structure-block structure (`dawnmine:spawn_hub`), loaded persistently if present with a procedural fallback when it is not. The mine itself, and every other system, stays fully procedural - this exception does not extend to anything else.
- Include all ores in the actual Rustic Craft 2 pack. Use common tags where present and explicitly catalog missing/unconventionally tagged ores.
- Integrate Born in Chaos using verified facts from the pack's exact artifact. Do not change its global spawn, AI, or loot behavior for entities outside the mine.
- Checkpoint storage is personal and persistent. Daily mine resets must not erase player inventories or personal Dispatch contents.
- Gameplay target: early depth is learnable; mining, PvE, and exploration feed durable gear/material progression; upgrades solve an identified earlier-depth problem while deeper regions introduce different readable tactical counters; deepest content rewards a coordinated team and this must be proven in multiplayer.
- Preserve vanilla death/inventory behavior in v1. Checkpoints create an informed bank-or-push choice; do not add a custom death penalty.
- Netherrack generation defaults off. If enabled, the hard ceiling is one attempt per 256 eligible 16x16 chunk columns at deepest tier, a cluster up to three blocks; admins can make it rarer, never more common or shallower.
- Provide friendly in-game admin help/setup/status/config/validation/recovery with server-side permission checks. Keep staff diagnostics private and player notices audience-targeted.
- The owner has authorized issue work to be committed and pushed to the remote and merged on GitHub as it is completed. For each issue, meet its acceptance criteria, pass the required build/test/dedicated-server verification, obtain a fresh independent flux-review pass, and get a complete flux-evaluate verdict before merging its PR. Do not merge when verification is failed, blocked, or unsupported. The owner has authorized GitHub releases (tester, beta, and stable channels). Production-server deployment remains unauthorized.

