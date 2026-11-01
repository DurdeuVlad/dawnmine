# Decision log

Status labels: accepted decisions are user-approved; proposals guide discovery but are not locked product requirements; open decisions need evidence or owner input before their milestone.

## Accepted decisions

### Progress across days through persistent capability

- Status: Accepted by owner direction (“leave with useful items so you can go even deeper the next time”).
- Decision: Daily terrain, mobs, loose drops, and run-local progress reset; carried equipment, mined progression materials, and private Dispatch contents survive. Gear should make earlier threats manageable while deeper regions introduce new problems.
- Consequence: Build the economy from actual pack recipes/items and test it across repeat runs. Do not assume an unverified steel/silver progression.

### Skill-based depth escalation and team-required endgame

- Status: Accepted by owner direction.
- Decision: Progression rewards mining, PvE, exploration, quick tactical decisions, equipment preparation, and group coordination. Early depth teaches; later difficulty comes from changing readable mechanics and threats. Deepest intended content requires coordinated players in actual playtests.
- Consequence: High mob health/damage alone is not evidence of depth difficulty or team dependency.

### Operator onboarding and Netherrack default

- Status: Accepted by owner direction.
- Decision: Provide friendly in-game help and permission-gated setup/config/status/validation/recovery commands. Netherrack is absent by default; if enabled, allow at most one placement attempt per 256 eligible 16×16 chunk columns in the deepest configured region, with a cluster up to three blocks. Operators may only make it rarer, up to a 65,536-column interval.
- Consequence: Config defaults, server-side permission checks, targeted messages, and safe failure behavior are acceptance requirements.

### Use a standalone server-side NeoForge mod

- Status: Accepted by owner.
- Context: Existing mine scripts are not the desired implementation; the workspace has only test-lab Java prototypes, not the feature's mod source project.
- Decision: Build a separate mod repository.
- Consequence: Do not modify the KubeJS faction-mine implementation.

### Share one Crystal Hollows-style mine

- Status: Accepted by owner.
- Decision: Everyone explores the same active generated world and run seed.
- Consequence: Generation, reset, checkpoint run progress, and resource availability are shared for the active run.

### Generate the environment procedurally

- Status: Accepted by owner.
- Decision: Build connected caves, tunnels, landmarks, stations, and hazards from code-generated block layouts. Do not require prefab structure files.

### Include every ore in the modpack

- Status: Accepted by owner.
- Decision: Account for all ores in the actual pack and assign them to depth tiers; include quartz in the Chaos Depths. Netherrack remains off by default under the bounded option above.
- Consequence: Runtime tags alone are insufficient proof. A pack artifact/catalog audit must identify untagged ores and explicitly include them.

### Integrate Born in Chaos and preserve outside behavior

- Status: Accepted by owner.
- Decision: Use verified Born in Chaos entities for mine encounters and depth progression. Limit mine-specific rewards and spawning to the mine.

### Daily morning reset and evacuation

- Status: Accepted by owner.
- Decision: Warn and teleport players still inside to the surface, clear/reseed the shared mine, and preserve player inventories and Dispatch storage.
- Default schedule: 06:00 in the configured server timezone; admins can change it through validated server configuration.
- Durable player equipment/material progression and private Dispatch contents are outside reset-owned state and must persist.

### Checkpoints and personal Dispatch storage

- Status: Accepted by owner.
- Decision: Checkpoints support teleporting into/out of the shared mine and accessing a personal persistent item locker at checkpoints and outside. First version uses exactly 54 private slots with no paid/upgrade expansion.

## Proposed technical defaults

### Use runtime/configured ore catalog, not name guessing

- Status: Proposed pending M0 pack audit.
- Proposal: Start from NeoForge common ore tags, compare with all installed mod artifacts, and add explicit resource IDs for missing ores. Assign tiers from a reviewed catalog; unknown entries must be surfaced in logs/reports rather than silently dropped.
- Rationale: Registry names are not a reliable ore taxonomy by themselves.

### Keep mob integration behind verified entity IDs

- Status: Proposed pending M0 artifact inspection.
- Proposal: Use public registry identifiers from the exact Born in Chaos jar; spawn selected mobs only through mine encounter logic.
- Rationale: Avoid a compile-time dependency on internal mod implementation classes and avoid changing global entity behavior.

### Use procedural block geometry for landmarks and stations

- Status: Proposed.
- Proposal: Build recognizable cave regions and service stations from block palettes and deterministic geometry algorithms.
- Rationale: Matches the user's no-prefab requirement while retaining visual variety.

## Open decisions

- Morning reset default: 06:00 in the configured server timezone, adjustable by a validated admin setting; status/help shows the effective next reset. This is the product default unless server evidence shows it is unsafe.
- Dimension versus isolated region placement, world bounds, maximum generation frontier, and cleanup strategy: M0/M1 technical decision backed by a disposable server prototype.
- Which Born in Chaos mobs fit each depth tier and which special encounters are safe in confined geometry: verify from the exact 1.7.6 jar and test in M4.
- Death and unbanked inventory behavior: first version retains vanilla rules; create no extra mod-specific death penalty.
- Exact upgrade chain, recipes/materials, and encounter/group thresholds: resolve from the M0 pack audit and multiplayer playtests; do not infer from item names.

## Revisit conditions

Revisit a decision only when pack evidence, a dedicated-server prototype, compatibility results, or player testing demonstrates that the current choice fails a stated goal or server safety limit. Record the evidence and owner approval here before changing the contract.

### Checkpoints are shared when generated

- Status: Accepted product decision based on the shared-world requirement.
- Decision: A checkpoint becomes usable by every player once it has been generated in the active shared run. Each Dispatch locker remains private to its player.
- Rationale: This matches a shared exploration world, lets players benefit from group progress, and avoids separate checkpoint-unlock state that would conflict with the shared map.

## Research references

- NeoForge 1.21.1 getting started and Java 21 requirement: https://docs.neoforged.net/docs/1.21.1/gettingstarted/
- NeoForge tags and common namespace guidance: https://docs.neoforged.net/docs/1.21.1/resources/server/tags/
- Born in Chaos version/platform listing: https://modrinth.com/mod/borninchaos/versions
- NeoForge 1.21.1 config system and server config behavior: https://docs.neoforged.net/docs/1.21.1/misc/config/
- NeoForge command registration and server permission predicates: https://docs.neoforged.net/docs/misc/commands/
- Target pack manifest: C:\Users\Vlad\Documents\Github\rustic-craft-2-modpack\modpack.json
- Target pack mod catalog: C:\Users\Vlad\Documents\Github\rustic-craft-2-modpack\catalog\mods.json
