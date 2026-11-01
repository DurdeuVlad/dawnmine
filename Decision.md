# Decision log

Status labels: accepted decisions are user-approved; proposals guide discovery but are not locked product requirements; open decisions need evidence or owner input before their milestone.

## Accepted decisions

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
- Decision: Account for all ores in the actual pack and assign them to depth tiers; include Nether ores such as quartz in the Chaos Depths.
- Consequence: Runtime tags alone are insufficient proof. A pack artifact/catalog audit must identify untagged ores and explicitly include them.

### Integrate Born in Chaos and preserve outside behavior

- Status: Accepted by owner.
- Decision: Use verified Born in Chaos entities for mine encounters and depth progression. Limit mine-specific rewards and spawning to the mine.

### Daily morning reset and evacuation

- Status: Accepted by owner.
- Decision: Warn and teleport players still inside to the surface, clear/reseed the shared mine, and preserve player inventories and Dispatch storage.
- Open parameter: exact reset time/timezone.

### Checkpoints and personal Dispatch storage

- Status: Accepted by owner.
- Decision: Checkpoints support teleporting into/out of the shared mine and accessing a personal persistent item locker at checkpoints and outside. Locker capacity must exceed a vanilla Ender Chest.
- Open parameter: final capacity and any expansion behavior.

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

- Exact daily reset hour and server timezone: owner input needed before M6 release; proposed default 06:00 local server time.
- Dimension versus isolated region placement, world bounds, maximum generation frontier, and cleanup strategy: M0/M1 technical decision backed by a disposable server prototype.
- Exact Dispatch slot count and expansion model: propose at least 54 slots, then review before M5 implementation.
- Which Born in Chaos mobs fit each depth tier and which special encounters are safe in confined geometry: verify from the exact 1.7.6 jar and test in M4.
- Death, respawn, and unbanked inventory behavior: unresolved product rule; keep existing Minecraft behavior until owner decides.

## Revisit conditions

Revisit a decision only when pack evidence, a dedicated-server prototype, compatibility results, or player testing demonstrates that the current choice fails a stated goal or server safety limit. Record the evidence and owner approval here before changing the contract.

### Checkpoints are shared when generated

- Status: Planning default inferred from the owner's accepted shared-world decision; owner may revise before M5.
- Decision: A checkpoint becomes usable by every player once it has been generated in the active shared run. Each Dispatch locker remains private to its player.
- Rationale: This matches a shared exploration world, lets players benefit from group progress, and avoids separate checkpoint-unlock state that would conflict with the shared map.

## Research references

- NeoForge 1.21.1 getting started and Java 21 requirement: https://docs.neoforged.net/docs/1.21.1/gettingstarted/
- NeoForge tags and common namespace guidance: https://docs.neoforged.net/docs/1.21.1/resources/server/tags/
- Born in Chaos version/platform listing: https://modrinth.com/mod/borninchaos/versions
- Target pack manifest: C:\Users\Vlad\Documents\Github\rustic-craft-2-modpack\modpack.json
- Target pack mod catalog: C:\Users\Vlad\Documents\Github\rustic-craft-2-modpack\catalog\mods.json
