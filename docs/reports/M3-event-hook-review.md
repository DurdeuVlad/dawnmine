# M3 pack break/drop hook review

## Evidence boundary

The source scan verified the 283 JARs in `M0-server-mods.sha256` by SHA-256,
then inspected 76,327 class files for direct references to
`BlockEvent.BreakEvent`, `BlockDropsEvent`, and block break/drop method names.
`M3-pack-bytecode-hook-scan.csv` retains every matching JAR/class/symbol row.
The live registry hook inventory in `M3-code-hook-scan.csv` covers all 23,256
registered block IDs, and `M3-ore-reconciliation.csv` records the 35
hook-bearing IDs that intersect the 99 ore candidates.

## Break-event references

The scan found 46 classes referring to `BlockEvent.BreakEvent`. These include
event API bridges and handlers for crop harvesting, the existing faction mine,
carcass/salt/rotisserie props, skeleton salvage props, hookah protection,
and other non-ore interactions. The candidate intersection classifies every
matching ore candidate. Its ore-related code-hook blocks are Dark Metal
Deposit and the two infected Diamond Ores; all three have exact-pack player
break results in `M3-player-break-audit.csv`.

## Drop-event references

Nine classes reference `BlockDropsEvent`; some are API/adapter references,
not handlers. The active behaviors are conditional:

- Backpacked can route drops into an equipped Lootbound backpack; it changes
  drop destination, not item IDs.
- Curios changes block-drop experience only.
- Hearth & Harvest replaces drops for grass/crops when a pitchfork is used.
- Hexalia adds drops only for blocks tagged as its crops while Green Omen is
  active.
- KubeJS exposes a generic drop callback, but no installed server script
  registers `BlockEvents.drops`.
- Tobacco suppresses creative-mode Hookah contents.

The target's InControl `breakevents.json` is empty. KubeJS and CraftTweaker
scripts contain no active ore-drop callback, and the CustomNPCs scripts folder
contains no player-break script. The player-break audit ran with the exact-pack
listeners loaded and an unmodified survival audit player. All catalog resource
outputs matched the recorded native item IDs; both encounter ores produced
only their recorded Diamond Termite encounter.

## Remaining static-scan limit

Direct bytecode references and registered block-class methods do not prove the
absence of reflective/generated listeners or mixins that patch generic
vanilla drop methods. Optional gear/effect combinations were not exhaustively
enumerated. No ore-specific global handler or unclassified ore candidate was
found, and the ordinary native ore outcomes were checked through the live
player-break path.
