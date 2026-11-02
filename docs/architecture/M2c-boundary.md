# M2c architecture: generated bedrock boundary

Scope: issue #22 (seal the public mine from caves and outside mining). M2b owns
the procedural route geometry and frontier. M2c adds a dimension-wide external
shell in a fresh, versioned dimension. M3 ore placement and M2c interaction
protections are separate milestones.

## Boundary ownership

The public-mine dimension uses `publicmines:bounded_flat`, a registered flat
chunk generator with `shell_radius_chunks` encoded in the dimension settings.
At radius 64, the owned square spans chunk coordinates -64 through 63 and block
coordinates -1024 through 1023 on X/Z. Every generated chunk receives a bedrock
floor at the minimum build height. Boundary chunks receive bedrock walls on
each external face, including corners, and a bedrock roof at the maximum build
height. Interior chunks receive the floor and roof. The shell is generated
using `ChunkAccess` during `fillFromNoise`, before the chunk becomes available
to players; it is not a large server-tick placement job.

The M2a topology profile keeps route nodes and corridors inset from the outer
owned bounds. M2b validates every generated operation against persisted
ownership. `MineManager` rejects startup when the configured radius, dimension
generator radius, or persisted run bounds disagree. Changing the dimension
radius after the world has generated chunks requires a new public-mine
dimension; the mod must not silently relocate a saved boundary.

## Cave behavior

The bundled dimension generator disables vanilla features, lakes, and
structure overrides; flat generation does not run cave carvers. The public
mine therefore creates no natural cave network that could connect the mine to
unowned terrain. Its rooms and passages are the only generated interior caves.
Pack validation must confirm that no datapack or mod replaces this dimension
generator or adds carvers/features to it.

## Fresh-dimension rollout and restart

The active dimension is versioned as `publicmines:public_mine_v2`. It is a new
dimension key, so chunks produced by an earlier experimental mine dimension
cannot become the active public mine. Existing old chunks are not incrementally
repaired or admitted; administrators must initialize a new shared run in the
v2 dimension. This avoids any period in which players could enter while an
old boundary is still being repaired. If a saved run refers to the old bounds
or dimension, normal manager validation fails closed and staff must recover a
fresh run before entry is enabled.

New v2 chunks receive the complete shell atomically in `fillFromNoise`. A
restart cannot interrupt the shell halfway through a chunk, and chunks created
after restart use the same generator settings. Run-generated interior
segments continue to use M2b's persisted progress/cursor and replay behavior.

The outer shell belongs to the dimension and is preserved across daily mine
resets. Reset must clear run geometry inside owned bounds without deleting the
floor, roof, or walls.

## Verification and limits

`MineShellGeometryTest` checks face and corner coordinates and outside
exclusion. Dedicated-server smoke checks loaded all four sides, a corner, and
the arrival chunk in the fresh v2 dimension. See
`docs/reports/M2c-dedicated-server-validation.md` for measured evidence and
remaining limitations.

The bedrock shell prevents ordinary survival mining through the boundary, but
does not claim protection from operator commands, creative-mode edits, or
other mods that deliberately bypass block interaction rules. Mine PvP, ore
allowlisting, torch placement, and safe-zone PvE rules belong to issue #23.
