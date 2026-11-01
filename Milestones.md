# Milestones

Target environment: Rustic Craft 2 2.2.2, Minecraft 1.21.1, NeoForge 21.1.248, Java 21. There are no dates or effort estimates. Product owner: mine product owner (Codex until explicitly replaced); implementer: Devin; independent review required before delivery. These outcomes are ordered by dependency. Read [Progression.md](Progression.md) as the player-experience contract for every gameplay issue.

## M0 — Standalone mod foundation and pack compatibility

**Outcome:** A separate server-side mod builds and loads beside the target pack; implementation has evidence for the exact ore, recipe, equipment, and Born in Chaos integration surfaces.

**Scope:** Java 21/NeoForge scaffold, dedicated-server smoke run, exact artifact/registry audit, ore and recipe/equipment inventory, server-side boundaries. No mine gameplay.

**Depends on:** None.

**Proof:** Clean build and dedicated-server load; checked-in report names exact artifacts and hashes, all ores/variants, relevant progression items/recipes/equipment, verified BiC entity IDs, and unknowns. Existing faction mine unchanged.

## M1 — Shared run lifecycle and operator onboarding

**Outcome:** Exactly one persistent shared run is isolated and recoverable, and a new admin can safely set it up and understand its state in game.

**Scope:** Issue #2 establishes run ownership/persistence, bounded placement decision, start/stop/recovery state, and safe entry/exit coordinates. New issue #12 adds `/publicmine help`, status, setup/validation, safe config inspection/change, permission checks, admin-only diagnostics, and targeted player guidance. Commands must be server-authoritative and safe to retry.

**Depends on:** M0.

**Proof:** Two players see one run; restart recovery is idempotent; cleanup owns only mine areas; a new operator completes setup using help and commands; player/staff/admin/console permission matrix is tested; invalid config fails safely with actionable help; player messages reach only the relevant audience.

## M2 — Procedural exploration

**Outcome:** One connected generated mine gives players legible routes, distinct regions, secrets, and useful landmarks without prefab structures.

**Scope:** Issues #3–4 implement deterministic block geometry for routes, branches, caverns, loops, landmarks, ore pockets, hazards, checkpoint/service alcoves, and bounded frontier expansion. Regions preview their risk/reward before commitment. No structure/prefab assets.

**Depends on:** M1.

**Proof:** Same seed reproduces topology; all routes connect and can be traversed; multiple players share cells; a test corpus checks seeds for invalid/unsafe paths; restarts and frontier limits remain safe.

## M3 — Complete pack ore ecology

**Outcome:** Every audited pack ore has a discoverable, native-feeling place in the depth progression.

**Scope:** Issue #5 maps every ore/block variant to a depth and placement rule, retaining native mining requirements/drops. Chaos Depths include quartz and contained lava hazards. Netherrack is disabled by default; if enabled, it allows at most one placement attempt per 256 eligible 16×16 chunk columns in the deepest tier, one cluster up to three blocks, and only rarer operator settings (up to one attempt per 65,536 columns). No global ore-generation changes.

**Depends on:** M0 audit and M2 regions.

**Proof:** Machine-readable pack-to-mine mapping has no omissions; generated runs contain the configured ore families in their intended tiers; quartz/lava can be found in Chaos Depths; default config yields zero Netherrack; enabled config cannot exceed validated extreme-depth/rate bounds; native drops/tools remain intact.

## M4 — Skill-based Born in Chaos encounters and mine rewards

**Outcome:** Encounters become more demanding by changing player decisions and coordination, not just mob stats; PvE contributes useful progression resources.

**Scope:** Issue #6 defines readable, depth-specific encounter compositions from verified BiC entities, using safe tactics/terrain/hazard interactions and strict caps. Deepest intended encounters place simultaneous demands on a coordinated group. Issue #7 awards bounded mine-only rewards that complement mining and feed M6 progression. Never modify global BiC spawns, AI, or drops.

**Depends on:** M0 verified entities, M1 ownership, M2 regions, and M3 tier catalog.

**Proof:** Every entity is verified and safe in tested geometry; encounter logs show distinct tactical changes across depth; solo/group test compares the deepest tier; a coordinated group can handle a challenge that is not a routine solo clear; mine reward attribution is idempotent and outside behavior is unchanged.

## M5 — Checkpoints, extraction, and Foreman's Dispatch

**Outcome:** Players can bank useful finds and make a real continue-or-extract decision during a shared run.

**Scope:** Issues #8–9 provide shared generated checkpoint travel, safe return to surface, and private persistent expanded storage at every checkpoint and the surface. Show current depth, next route/risk, and the bank action. Preserve vanilla death/inventory rules in v1; apply no custom death penalty.

**Depends on:** M1 state and M2 stations. M6 consumes the persistent banked items this milestone provides.

**Proof:** Multiplayer tests prove shared checkpoint discovery and private lockers; banked resources survive relog, restart, and daily reset; full/disconnected transactions neither duplicate nor delete items; solo/group sessions record informed bank-or-push decisions and recovery.

## M6 — Durable equipment and depth progression economy

**Outcome:** Players convert banked finds from mining, PvE, and exploration into useful capability, then encounter new problems deeper down.

**Scope:** New issue #13 inventories actual pack recipes/items/equipment, maps resource sources to upgrades and depth counters, and adds only the smallest cohesive mine-specific equipment/recipe additions if the pack lacks a suitable path. All example metals/tiers must be evidence-backed. Progression persists across daily resets; it does not grant permanent depth skips or make new tiers trivial.

**Depends on:** M0 recipes/items, M3 ore tiers, M4 reward sources, and M5 extraction/storage.

**Proof:** A reviewed progression graph links every material to a useful output; each upgrade names an earlier problem it improves and a later counter/challenge it does not solve; a repeat-run progression playtest demonstrates a meaningful deeper attempt; mining, PvE, and exploration each have useful contributions; reset tests preserve capability and banked materials.

## M7 — Configurable morning reset

**Outcome:** One fresh shared expedition begins each configured morning while durable player progress remains safe.

**Scope:** Existing issue #10 becomes M7: default 06:00 in configured server timezone, configurable warnings, entry lock, safe evacuation, clear/reseed only mine-owned state, and downtime/restart recovery. Preserve carried equipment/materials, Dispatch, and factions mine.

**Depends on:** M1 owned-state transaction, M2 generation, M5 travel/storage, and M6 durable progression.

**Proof:** Schedule boundary, online, downtime, interrupted reset, and retry tests show one reset per server-local date; correct audience sees warnings; every player is safely evacuated; carried gear, materials, locker contents, and faction-mine fixtures are byte/state-equivalent across reset.

## M8 — Playtest balance and operator readiness

**Outcome:** The complete loop has evidence that players learn, adapt, cooperate, bank, improve, and want another expedition; admins can safely operate it.

**Scope:** Existing issue #11 becomes M8: repeated solo and group playtests across the full depth curve and multiple days; tune only from observations; record performance, economy, reset, permissions, targeted messaging, onboarding, recovery, and faction-mine regression. Write a friendly in-game and operator runbook. This is a verification milestone, not a promise that fun can be inferred from compile success.

**Depends on:** M0–M7.

**Proof:** Playtest cards and feedback document learnability, route decisions, mining/PvE contributions, upgrade effect, new counters, extraction choices, repeat-session intent, and deepest group-vs-solo outcomes. Real pack dedicated-server runs meet agreed budgets; admins can set up/recover without editing source; all open risks are recorded and release gate passes independent Flux review/evaluation.

## Current execution

- M0 is active with Devin; it remains foundation and compatibility work only.
- All later gameplay milestones are plans, not implemented behavior. Update the relevant GitHub issue and implementation prompt before each new Devin task.
- No release or production deployment is authorized.

## Issue map

- M0 #1 — [Standalone mod foundation](docs/issues/M0-foundation.md)
- M1 #2 — [Shared run lifecycle](docs/issues/M1-shared-run-lifecycle.md); #12 — [Admin onboarding and control](docs/issues/M1-admin-onboarding.md)
- M2 #3 — [Procedural topology](docs/issues/M2a-procedural-topology.md); #4 — [World generation](docs/issues/M2b-world-generation.md)
- M3 #5 — [Ore ecology and Netherrack config](docs/issues/M3-ore-ecology.md)
- M4 #6 — [Skill-based BiC encounters](docs/issues/M4a-bic-encounters.md); #7 — [Mine-only rewards](docs/issues/M4b-mine-rewards.md)
- M5 #8 — [Checkpoints and extraction travel](docs/issues/M5a-checkpoints-travel.md); #9 — [Foreman's Dispatch](docs/issues/M5b-dispatch-locker.md)
- M6 #13 — [Equipment and depth progression economy](docs/issues/M6-progression-economy.md)
- M7 #10 — [Daily reset](docs/issues/M6-daily-reset.md)
- M8 #11 — [Pack playtest and operations readiness](docs/issues/M7-pack-validation.md)
