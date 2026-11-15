# M12g Issue Draft — The entrance must commit forward, away from the hub

## Planning metadata
- Issue type: Bug / navigation and readability
- Priority: Medium
- Milestone: M12 — Owner playtest fixes: ore density, mob difficulty/drops, loot, layer connectivity, mob turns, mob variety
- Assignee: Unassigned
- Target date: Not set

## Intent

Owner playtest found that right after leaving the hub/safe zone, cave generation can wind back toward the hub area instead of committing forward, which is confusing on first entry and risks the open cave reading as part of the safe zone.

## Expectation

Immediately past the hub's forecourt/gateway, the first stretch of cave is a forward-committed corridor that does not double back toward the hub box, so a new player's first steps always read as "heading away from spawn," before the terrain opens into the normal winding noise-carved caves.

## Context that code cannot infer

- Confirmed structural facts (from reading `CavePlanner.java`/`CaveStack.java`): layer 0 keeps a closed hub box with a pinned, open forecourt east of the hub gateway (`CaveStack.hubBox`, `CavePlanner`'s own doc comment: "the hub box is closed rock; a pinned forecourt east of the hub gateway is open"). The hub box itself cannot be carved into by any other system (same keepout pattern used throughout `CaveStack`/`CavePlanner`), so the complaint is not that terrain enters the safe zone's actual protected volume — it's that the open, winding noise-carved terrain immediately beyond the forecourt is not direction-constrained, so a path can curve back near/around the hub box before heading away, which reads as "generation going back into the safe zone" even though the safe zone itself stays intact.
- The exact mechanism that currently shapes the area right past the forecourt (how far the noise-carved terrain is left unconstrained before the layer's `ridge`/spine logic takes over) needs confirming during implementation — this issue intentionally does not claim a precise root-cause line, since that needs a closer read of `CavePlanner`'s terrain-shaping pass specifically in the region between the forecourt and the layer's main ridge, as a `flux-discovery` step before committing to a fix.
- This is distinct from M12e's dropper/layer-transition work — this issue is about the very first stretch of layer 0, right past spawn, not about inter-layer connectors.

## Scope

- Investigate (flux-discovery) exactly how terrain is shaped in the region immediately east of the hub forecourt on layer 0, and confirm the specific cause of back-toward-hub winding.
- Add a forward-commitment constraint for a fixed minimum distance past the forecourt (e.g. a direction bias or a short literal straight/gently-curving corridor segment) before handing off to the normal unconstrained noise-carved cave shaping, so the first stretch cannot curve back toward the hub box.
- Keep this constraint scoped to the entrance region only — it must not change the normal winding character of caves elsewhere in the layer, which is an intentional design pillar (`Mine-Concept-v2.md` section 10.3: "vanilla Minecraft cave system... big noise caves").

## Non-goals

No change to the hub box's own protected volume or the forecourt's existing dimensions. No change to winding cave character anywhere outside the entrance region. No change to the three-descents-per-pair pacing system (M12e's scope, not this issue's).

## Dependencies and open decisions

- Depends on M11-1/M11-2 (`CavePlanner`, `CaveStack`) for the terrain-shaping pass this issue modifies.
- Independent of M12a-M12d and M12f; loosely related to M12e (both touch early-layer navigation) but should not block on it.
- Open decision: exact minimum forward-commitment distance before normal winding resumes — implementer proposes a value (e.g. enough that the hub box is no longer visible/nearby) and the owner confirms by playtest.
- Product owner: Durdeu Vlad-Ioan. Implementer: the next agent assigned this issue. Reviewer: independent.

## Acceptance criteria

1. A newly generated mine's first explorable stretch past the hub forecourt does not curve back within a short, defined distance of the hub box.
2. The hub box's own protected volume remains fully intact and uncarved (no regression to existing keepout guarantees).
3. Cave character beyond the entrance-commitment stretch is unchanged from today (still noise-carved, winding, per the existing design pillar).
4. `/dawnmine census` reachability and ore/mob density numbers for layer 0 are unaffected outside the entrance region (no regression to the existing near-full reachability baseline recorded in M12e).

## Verification

Add a unit/GameTest asserting the entrance region's generated path does not re-approach the hub box within the defined minimum distance, across multiple seeds. Manual playtest by the owner confirming the first steps out of the hub read as "heading away," not "looping back."

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
