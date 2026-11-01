# M2a Issue Draft — Deterministic procedural topology

## Planning metadata
- Issue type: Feature / algorithm
- Priority: High
- Milestone: M2 — Crystal Hollows-style procedural exploration world
- Assignee: Unassigned
- Target date: Not set

## Intent

Give the shared mine discoverable routes and regions that feel like a large mining zone rather than a strip-mined tunnel, while ensuring every generated area remains connected and repeatable during one daily run.

## Expectation

A seed-driven topology planner produces a connected graph of route segments, caves, branches, caverns, resource-field locations, landmarks, checkpoint locations, and region/depth labels. All players share the same plan for the active seed. Generation uses algorithmic layouts and block palettes, not prefab structure files.

## Context that code cannot infer

- The user referenced the Crystal Hollows exploration model and approved a shared world.
- Procedural block geometry is a product constraint; no authored building assets.
- Daily seed replacement belongs to M6; M1 owns saved run identity.
- The actual mine bounds and maximum generation frontier are recorded by M1.

## Scope

- Define a deterministic topology representation separate from block placement.
- Generate a traversable main route with optional branches, loops, resource pockets, landmarks, and checkpoint stations.
- Assign upper, middle, and Chaos region labels and a logical depth progression.
- Validate graph connectivity, bounds, duplicate coordinates, and required route continuity before block placement.
- Add deterministic unit/property tests for fixed seeds and boundary cases.

## Non-goals

No ore block selection/distribution, mob tables, GUI, storage, daily scheduler, custom textures, or predefined building files.

## Dependencies and open decisions

- Depends on M1 world bounds and run-state contract.
- M2b consumes this topology representation.
- Exact number/size of regions may be tuned from playtesting, but region progression must be data-driven.
- Owner: server owner. Implementer: Devin. Reviewer: independent.

## Acceptance criteria

1. Same seed and configuration produce byte-for-byte equivalent topology output across restart and repeated generation.
2. All main-route nodes are reachable from the entrance; optional branches connect to a route or are intentionally marked dead ends.
3. Every node stays within M1's mine-owned bounds and configured generation budget.
4. Each daily run has upper, middle, and Nether-themed Chaos region labels with valid transitions.
5. The topology can be validated before world mutation; invalid plans fail without partially placing blocks.
6. Tests cover fixed seeds, boundary edges, route connectivity, and generation failure inputs.
7. No prefab structure asset is required.

## Verification

Run the deterministic and graph-property test suite over fixed and randomized seeds. Save representative topology snapshots for review. Verify all generated nodes fit M1's documented bounds.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
