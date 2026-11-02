# M2c: Seal the mine from caves and outside mining

### Planning metadata
- Issue type: Feature / world containment
- Priority: High
- Milestone: M2c — Mine isolation and fair-play rules
- Assignee: Unassigned
- Target date: Not set

### Intent

Keep the shared mine an intentional, explorable route. Players must not enter from outside by mining through the shell or bypass route progression by entering an intersecting natural cave.

### Expectation

Where the mine encounters a natural cave, the cave volume inside the generated mine footprint is fully replaced by mine geometry. Any natural cave beyond that footprint is sealed behind a continuous external bedrock shell, with openings only at explicitly generated or configured entrances. Players can explore generated routes from inside, but cannot mine into or out of the mine.

### Context that code cannot infer

- This is one shared, bounded public mine; the factions mine and all unrelated worlds are outside the ownership boundary.
- M2b owns topology-to-block generation and frontier expansion. This issue adds containment at the mine/world boundary.
- The playable interior is inset from the full run-owned bounds, which include the entire external shell; both areas are covered by saved bounds, cleanup, reset, and recovery.
- No prefab buildings or authored structure files. Geometry and entrances remain procedural or configured coordinates.
- Preserve intended surface access and travel/checkpoint workflows.

### Scope

- Detect natural cave volumes intersecting the generated mine footprint and replace the overlapping volume completely with generated mine geometry.
- Seal any natural cave space remaining beyond the generated footprint so no accessible connection bypasses the intended mine route.
- Build a continuous external bedrock shell around the playable mine interior, including faces and corners, except for explicit entrance/exit apertures. The full shell must lie within the run-owned bounds.
- Include shell and replaced interior in idempotent generation, interruption recovery, and mine-only cleanup.
- Reject plans whose shell or generated geometry would escape the bounded run-owned area; expose actionable diagnostics.

### Non-goals

No edits to vanilla cave generation outside the mine-owned boundary, no changes to other dimensions or the factions mine, no player block permission rules (tracked by #23), no prefab buildings, and no new entrance design beyond preserving the approved mine access path.

### Dependencies and open decisions

- Depends on #4 (M2b topology-to-block generation and owned bounds).
- Product owner: Codex. Implementer: Devin. Reviewer: independent.
- Shell thickness and exact entrance apertures are implementation decisions; they must produce a continuous non-bypassable boundary and remain inside recorded run ownership.

### Acceptance criteria

1. Every natural cave volume overlapping the generated mine footprint is replaced by mine geometry; any adjacent natural cave outside the footprint is inaccessible from the mine and outside world through the shell.
2. A continuous bedrock shell encloses the mine on all external faces and corners, with only documented entrances open.
3. A survival player cannot mine through the shell to enter from outside or escape the mine; intended inside exploration and approved entrances remain usable.
4. Generation, restart recovery, cleanup, and reset handle the shell and cave replacement idempotently, without editing blocks outside recorded ownership.
5. The playable interior is inset from configured/persisted run-owned bounds far enough to contain the whole shell; shell and generated interior remain inside owned bounds, and invalid bounds fail closed before world mutation.
6. Dedicated-server checks cover a natural-cave intersection, each shell face/corner, intentional entrance traversal, restart during generation, cleanup, and unchanged adjacent/factions-world fixtures.

### Verification

Use deterministic world fixtures or generated test worlds with natural-cave connections on multiple sides. Attempt survival-mode mining from outside and inside, walk all internal routes, verify approved entrances, restart during partial shell placement, reset the run, and compare blocks beyond the owned boundary.

### PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, ownership, dependencies, commands/results, and residual risks.
