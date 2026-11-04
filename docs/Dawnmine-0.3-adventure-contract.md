# Dawnmine 0.3: An Underground Adventure

## Goal

Replace the small room-and-straight-tunnel experience with a large, connected,
Minecraft-like cave and abandoned-mineshaft expedition. This supersedes the
old visual-generation target, not inventory safety or server isolation.

The owner also selected the identity `Dawnmine`, package
`com.dwurdy.dawnmine`, and command `/dawnmine` without a `/publicmine` alias.

## Experience

- Enter an already prepared underground landscape, not a construction site.
- Follow winding, irregular natural passages into broad, tall caverns.
- Find abandoned timber-supported workings, rails, bridges, and lit landmarks
  amongst natural stone rather than a uniform grid of identical rooms.
- Choose between several branches, loops, resource detours, and a deeper route.
- See native ore veins in exposed walls throughout exploration, including
  the first descent. Resource fields are richer, not the sole source of ore.
- Descend from stone galleries through deepslate into readable Chaos hazards,
  with shared checkpoints and private persistent Dispatch storage.

## Underground Hub And Teleporters

Players arrive inside a generated 45x45 expedition hub, not on the old
surface pad. The standard arrival is `(0,42,8)`, above the Y41 floor and
below a covered roof at or below Y59. A portal hall, gathering space,
private Dispatch console and east cave gateway give the hub distinct uses.
The whole hub is protected against hostile combat and terrain modification.

Use vanilla blocks and server-side interaction: a staff-bound overworld
lodestone enters through the existing safe-travel path; the hub lodestone
returns to the recorded entry point, with the existing safe surface fallback.
The portal arch is architectural space, not an active Nether/End portal.
The hub barrel opens a per-UUID private locker, never shared barrel storage.

Staff place their own surface lodestone and bind it in-game with
`/dawnmine teleporter bind <x> <y> <z>` (permission level 2, within six
blocks, overworld only, safe adjacent landing). No overworld structure is
generated or overwritten. The binding persists separately as
`dawnmine_teleporter.dat`, independent of daily run resets. Unbinding changes
the link and safe-zone metadata, not world blocks.

Recognized consoles consume their interaction without opening vanilla
container behavior. Main-hand clicks activate them; off-hand barrel clicks
cannot bypass locker privacy. A success-only cooldown prevents double-fire
without blocking unrelated block use. Entry remains closed throughout
preparation and reset; return travel and `/dawnmine leave` remain escape
paths. All travel closes open menus without losing carried items; a stack
held on the cursor is escrowed during the dimension transition and restored
at the destination even if another listener closes the menu mid-travel.

Hub geometry and its gateway are required preparation work. Other cave,
dressing and ore operations respect its reserved volume. The standard hub
is fixed at Y42; profiles/bounds incompatible with the whole hub fail
validation rather than moving only the entrance into mismatched geometry.

## Architecture And Decisions

Keep the deterministic topology and its saved run identity. The topology
module owns connectivity; geometry owns cave shapes, traversable winding
paths, and mineshaft block intents; the server generator owns chunk loading,
bounded mutation, provenance, and preparation completion.

Use whole-run preparation before admission instead of trying to outrun
players with a nearby carving frontier. This intentionally changes the old
"expands as players explore" implementation: exploration reveals finished
terrain. No terrain writes occur during ordinary exploration of a prepared
run. Daily reset prepares another run with a new shared seed.

Queue structural carving first, ore/decorating work after the structures
that could bury or overwrite it. Admission, checkpoint travel, login
recovery, setup guidance, and player status must agree about preparation.
Never present merely initialized SavedData as a playable finished run.

Use bounded asynchronous chunk requests/tickets owned by the generator;
release them on completion, failure, run change, reset, and shutdown.
Retain persisted progress and deterministic replay. Do not synchronously
load the entire mine or keep its entire chunk field force-loaded.

Extend existing geometry rather than add a second competing generator.
Use deterministic varying cave cross-sections, connected lobed caverns,
and winding paths with a supported walkable centerline. Structural frames
must leave two-block player clearance and must not block junctions.
Keep the hub landing and checkpoint landing volumes safe.

The dimension's base terrain is real Minecraft cave world generation:
dawnmine:run_noise drives vanilla noise generation (the caves noise
settings, cave/canyon carvers, and a dedicated cave biome) under the
current run's seed, so each daily run produces genuinely different cave
terrain while remaining deterministic inside one run across restarts.
Vanilla mineshaft structures are placed deliberately at run-seeded sites
inside the adventure space - natural structure placement stays off via
the dedicated biome - and no shaft may overlap the hub reserve. The
daily reset restores owned volumes to the new run's noise baseline by
recomputing generator output per column, then preparation carves the
guaranteed hub, routes, checkpoints, and ore plan through that terrain.

Where nearby passage floors conflict, resolve them into supported junctions
and one-step ramps using the same physical coordinates for carving, dressing,
ore planning, stations and encounters. Account for the player's body sweep
at diagonal corners, not only centerline endpoints. Preserve the fixed hub
gateway; fail closed if bounded resolution cannot produce valid geometry.
A path always arrives at its node room: resolution may lower an endpoint
into a nearby crossing but may never raise it away from its node.

The standard profile should use 18/22/20 main nodes, 5/6/6 caverns,
8/10/10 branches, 4/5/6 loops, 8/10/12 pockets, 8/10/12 resource fields,
3/3/4 landmarks, and one checkpoint per depth tier. Branch length cap is
four, route steps 32-72 blocks, maximum nodes 400 and total corridor budget
80,000. Keep a horizontal margin of at least 36 blocks for larger geometry.
The compiled fallback and bundled profile must agree.

Treat these as design inputs, not measured claims of quality or runtime
performance. The verification report must record actual results.

## Compatibility And Scope

Minecraft 1.21.1, NeoForge 21.1.248, Java 21; server-side only.
Keep native ore drops, Born in Chaos integration, protection rules,
daily scheduling, inventory behavior, locker privacy, and factions isolation.
Keep netherrack off by default and retain its existing hard rarity ceiling.

Rename current first-party code, resources, config, data names, diagnostics,
commands, artifact, and current operator docs to Dawnmine. Genuine
third-party pack identifiers and historical evidence are not to be rewritten
as if the past tests ran a different build.

Changing namespace/dimension is a breaking migration. Never delete old
dimensions, world files, or player data. Provide backup-first offline upgrade
instructions, evacuating players with the old build before replacement.
Dispatch transfer must be non-overwriting and preserve the old file.
Do not silently copy an old run receipt into incompatible geometry.

## Acceptance Evidence

- Determinism across repeated generation and variation between seeds.
- Connected walkable routes, bends, branching cycles, significantly larger
  cavern extents, mineshaft features, and preserved landing clearances.
- Exposed native ore in normal passages and the entrance route, outside
  protected pads; richer resource fields; no structural overwrite or
  provenance-capacity failure in the complete standard run.
- Entry denied before preparation finishes, progress reported honestly,
  preparation resumes after restart, and reset does not admit players early.
- Bounded chunk work and released tickets; no exploration-triggered carving
  after preparation, including with multiple players at different locations.
- Build and unit tests plus new dedicated-server checks on a disposable
  exact-pack world. Record duration, errors, ore observations, and limits.
- Preserve existing protection, locker, native-break, and reset regressions.
- Prove physical surface-console entry, interior arrival, return-console exit,
  cooldown, unchanged inventory, private hub lockers and binding persistence.
- Supply inspectable terrain evidence; a schematic or cross-section is not
  a substitute for an in-game visual playtest. If a real client is unavailable,
  explicitly keep player-perceived adventure quality unverified.

## Delivery Boundary

Work locally on a feature branch. Do not bypass the owner's main-branch
review protections. Stage a 0.3.0 jar, checksum, changelog, upgrade guide,
and evidence. Commit, push, PR, public release and production deployment
are separate authority actions, not consequences of a passing build.
Public release and production deployment are not authorized by this contract;
obtain explicit owner authorization and satisfy the verification/review gates
before any such action.
