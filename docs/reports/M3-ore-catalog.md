# M3 ore catalog and reconciliation

## Target pack and discovery evidence

The target is Rustic Craft 2 2.2.2, Minecraft 1.21.1, NeoForge 21.1.248. M0
records 283 target-pack JARs and SHA-256 hashes, a runtime scan of 23,256
registered blocks and 1,399 block tag IDs, and loot-table discovery across the
registered block inventory. All 99 M0 ore candidates are classified in
`M3-ore-reconciliation.csv`, with per-candidate hook and player-break evidence.

The runtime catalog has 32 typed entries: 30 native resources and two
encounter ores. The 30 resources cover all 23 resolved `c:ores` family blocks
(21 direct members plus two `c:ores/silver` members) and seven explicit
resources outside that family: Truffle Dirt, Seabrass Ore, two Sulfur Ore
variants, Dark Metal Deposit, and two Jewelry gem veins. The two Born in Chaos
infected Diamond Ores are `ENCOUNTER` entries for Chaos, each with its required
tool and `born_in_chaos_v1:diamond_termite` entity. `OrePlacementPlanner`
selects only `RESOURCE` entries; M4a owns encounter placement and mine-origin
tracking so native break hooks cannot create untracked mobs.

Completeness checks the resolved `c:ores`, `c:ores/silver`, and
`fungidelight:truffle_ore` tags, every catalog block ID, all three native
resource tiers, every declared native drop item ID, and each encounter entity
ID. An unavailable block, drop item, or encounter entity makes completeness
fail; it cannot be hidden by a randomized drop sample. This closes the
common-tag and explicit-mod-tag gaps for the pinned target pack.

## Drop-source reconciliation

The live registry scan examined all 23,256 blocks and found 1,525 blocks with
class-defined break/drop methods, with zero scan errors. The full inventory is
`M3-code-hook-scan.csv`. Its candidate intersections are recorded in the
reconciliation; the ore-related hook blocks are Dark Metal Deposit and the
two infected ores.

A separate scan hash-verified the 283 target-pack JARs and inspected 76,327
class files for `BlockDropsEvent`, `BlockEvent.BreakEvent`, and related block
drop methods. The 533 matching class/symbol rows are in
`M3-pack-bytecode-hook-scan.csv`; the summarized behavior review is
`M3-event-hook-review.md`. The scan found nine classes referring to
`BlockDropsEvent` and 46 referring to `BlockEvent.BreakEvent`. The active
handlers are conditional and limited to drop destination, XP, crops, or
creative Hookahs; InControl's break-event rules are empty, and no installed
KubeJS ore-drop callback exists. Exact-pack player-break tests below exercised
the ore candidates with those pack listeners active.

Static scans cannot prove the absence of behavior hidden behind reflection,
runtime-generated listeners, or mixins patching generic vanilla drop methods.
The complete candidate inventory has no unresolved ore candidate or
ore-specific global drop handler, and each catalog resource outcome was
observed through the server-authoritative player-break path.

## Placement and native behavior

Only topology nodes marked `RESOURCE_FIELD` receive native resources. Each
field plans two deterministic exposed-wall veins selected from its upper,
middle, or Chaos tier. The bundled-catalog planner test exercises resource
fields in all three tiers, verifies that every planned ID belongs to the
node's tier, and confirms encounter entries cannot enter ordinary ore veins.
The JSON catalog preserves native blocks, mining requirements, and item IDs.
Jewelry entries carry the complete randomized gem output pool from the pack's
active loot tables. Weights are relative selection weights, not measured
economy rates; this report makes no balance claim.

The exact pack ran with the 283 hash-verified target JARs plus the Public Mines
artifact under test (284 JARs total). The tested mod artifact SHA-256 is
`BE62B0AF1B0382559CE1858BD1737759F9AE6E9EDAF1FA1C55544DEB9E33963A`. The
dedicated server loaded 32 entries with fingerprint `15661c507ce0`. An RCON
`/reload` loaded the same catalog fingerprint again, verifying the resource
reload path against the exact pack.

After reload, the gated audit passed 60 authoritative player-break cases:
each of the 30 native resources was tested with a matching tool, each of the
26 tool-required resources was also tested with a stick, and the four no-tool
resources were tested empty-handed. Both encounter ores were broken with a
diamond pickaxe and a stick; each spawned only the expected Diamond Termite
and produced no item drop. The audit also confirmed the full 23,256-block
registry scan found 1,525 hook-bearing blocks with zero scan errors. Retained
results are `M3-player-break-audit.csv`, `M3-player-break-audit.log`, and
`M3-exact-pack-runtime.log`.

## Chaos material and Netherrack

Nether Quartz Ore is assigned to Chaos. Lava remains a separately planned
Chaos-cavern hazard with a basalt floor and boundary; geometry tests verify
the containing ring and owned-volume bounds. Netherrack is separate from ores,
disabled by default, and uses the bounded interval 256–65,536 with at most one
three-block cluster per eligible deepest-tier chunk column. Tests verify the
disabled default, deterministic selection, bounds, and depth restriction.

## M3 evidence status

- Reconciliation: all 99 discovery candidates are classified; no unresolved
  candidate remains.
- Catalog: all 32 block entries and two encounter entity IDs resolve against
  the pinned runtime. All known ore tags are checked for omissions.
- Reload: startup and dedicated-server `/reload` both load the same catalog
  fingerprint and preserve completeness.
- Mining: 60 exact-pack cases pass, including suitable and unsuitable tools,
  native drops, and both Born in Chaos encounter hooks.
- Placement: the bundled production catalog is exercised in all three
  resource tiers; encounter entries remain excluded until M4a integrates
  encounter-site placement and mine-origin cleanup.
- Limits: no economy balance rates are claimed. The exact target server ran
  without a graphical client; group combat and economic balance remain M8
  playtest work.
