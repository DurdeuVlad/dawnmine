# M2b architecture: procedural mine generation

Scope: issue #4 (procedural block generation and frontier expansion). M2a
owns the deterministic graph; M2b turns it into shared mine geometry and
queues the next route segments as players approach. M2c owns the external
bedrock boundary and interaction protections. Later milestones own ores,
encounters, rewards, checkpoint travel and resets.

## Component map

```text
generation
├── SegmentGeometry   pure deterministic block-operation plans for nodes,
│                     edges and the arrival connector
└── MineGenerator     validates plans, schedules slices, expands the frontier,
                      writes only loaded chunks inside MineRunData ownership

MineRunData (schema 3)
├── pinned generation fingerprint and palette settings
├── FIFO pending segment IDs and completed segment IDs
└── one active segment ID + operation cursor
```

`SegmentGeometry` contains no Minecraft world mutation. Given the topology,
owned bounds and arrival coordinates, it returns stable segment IDs and
sorted block operations. Rooms, caverns, pockets, checkpoint alcoves, edge
corridors and the entrance connector all derive from topology. Checkpoints
include a supported 5x5 pad and three-block clearance. Chaos caverns create a
lava source behind a basalt floor and ring; the boundary shell that protects
the rest of the mine is a separate M2c feature.

## Scheduling and recovery

`MineGenerator` runs after each server tick. At startup it replans M2a,
validates the topology receipt and pins the geometry algorithm fingerprint
and Netherrack options to the run. Configuration changes therefore affect a
new run rather than changing the meaning of a partially generated one.

Only one segment is active. Progress is persisted as a cursor, and a restart
replays that active segment from its start. Reapplication is idempotent:
already-matching blocks are skipped, and non-palette content is preserved.
Completed segment IDs are deduplicated from both pending and active work.
Malformed or over-limit persisted queues/cursors fail closed.

Each tick processes at most eight operations and checks a five-millisecond
soft slice budget after each block operation. A single slow block update can
exceed that time budget; it cannot be preempted on Minecraft's server thread.
The scheduler checks `ServerChunkCache.hasChunk` and pauses at the first
unloaded block without requesting chunk generation. Completed nodes near a
player enqueue adjacent edges; completing an edge enqueues its endpoint
rooms. Thus the route grows on demand and remains shared across players.

Writes must be inside a recorded owned volume and outside the arrival-pad
keepout. A violation fails the run before writing that operation. Chaos uses
the basalt, blackstone and deepslate palette; optional Netherrack clusters
are deterministic, run-pinned and limited to three blocks.

## Diagnostics

`/publicmine status` reports completed, queued and active segment progress.
The server log profiles each completed segment with operation/write counts,
maximum operations per tick, total and maximum slice time, maximum operation
time, and the configured count/time limits. Generation validation errors are
reported through the shared run's FAILED state and last-error diagnostics.

## Validation limits

Pure unit tests cover deterministic geometry, stable IDs, walkable checkpoint
pads, connector and edge continuity, bounds, arrival keepout, lava containment
geometry, cursor replay, persistence, migration and malformed data. Dedicated
server evidence verifies a real custom-dimension run and generated arrival
connector/entrance room. Player-triggered expansion, multiplayer contention,
real liquid flow, the full Rustic Craft modpack and Born in Chaos integration
are not yet validated by M2b; M3/M4 and M7 remain downstream work.
