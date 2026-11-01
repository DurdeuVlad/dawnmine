# M3 Issue Draft — Complete pack ore ecology

## Planning metadata
- Issue type: Feature / resource progression
- Priority: High
- Milestone: M3 — Complete modpack ore catalog and depth ecology
- Assignee: Unassigned
- Target date: Not set

## Intent

Players should be able to find every ore in Rustic Craft 2 by exploring the shared mine, with better and rarer resources placed deeper and Nether ores appearing in the Chaos Depths.

## Expectation

The server has a reviewed ore catalog derived from runtime common tags and M0's pack audit. Every catalog entry has a tier, concrete block variants, and a distribution policy. Mining uses the registered native blocks and preserves their ordinary mining requirements and drops.

## Context that code cannot infer

- Target pack: Rustic Craft 2 2.2.2, Minecraft 1.21.1, NeoForge 21.1.248.
- User requirement: all ores in the pack, including modded ores; quartz and Nether-theme materials in Chaos Depths.
- Common tags may omit modded ores; M0's artifact audit is part of the completeness proof.
- Ore rates affect server economy and require review; do not invent high rates.

## Scope

- Implement registry/tag-backed ore discovery and refresh behavior.
- Add explicit ID entries for audited ores missing from common tags.
- Map every ore to depth tiers and variants in a data-driven catalog.
- Populate M2 resource pockets using native ore blocks; include quartz and approved Nether ore blocks in Chaos.
- Add a completeness diagnostic that reports unassigned, missing, or unavailable entries.

## Non-goals

No new ore blocks, replacing loot tables, global worldgen modifications, artificial raw-ore mob rewards, or economy changes outside the mine.

## Dependencies and open decisions

- Depends on M0 pack ore audit and M2 region/resource-pocket placement.
- Final distribution weights require server-owner review using playtest evidence.
- Owner: server owner. Implementer: Devin. Reviewer: independent.

## Acceptance criteria

1. The runtime ore catalog reconciles against M0's exact pack audit; every known pack ore is assigned or the issue remains blocked with explicit missing IDs.
2. Each assigned ore appears in its intended depth tier; no ore is silently omitted due to tag reload or namespace differences.
3. Nether quartz and approved Nether ores appear only in the intended Chaos region(s).
4. Mining an ore uses that block's native hardness, harvest/tool rules, loot, and mod behavior.
5. Generated rates are documented and configurable; no unsupported claim of economic balance is made.
6. Tests cover tag reload, missing IDs, duplicate variants, tier assignment, and generation restrictions.

## Verification

Run catalog comparison against the target dedicated server's live registry. Generate sample seeds for each tier, mine representative vanilla/modded/Nether ores with appropriate and inappropriate tools, and capture drops and rates.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
