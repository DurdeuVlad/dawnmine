# M3 Issue Draft — Complete pack ore ecology

## Planning metadata
- Issue type: Feature / resource progression
- Priority: High
- Milestone: M3 — Complete modpack ore catalog and depth ecology
- Assignee: Unassigned
- Target date: Not set

## Intent

Players should be able to find every ore in Rustic Craft 2 by exploring the shared mine, with better and rarer resources placed deeper and quartz/contained lava in the Chaos Depths. Netherrack is disabled by default and may be enabled only through a bounded, ultra-rare extreme-depth server setting.

## Expectation

The server has a reviewed ore catalog derived from runtime common tags and M0's pack audit. Every catalog entry has a tier, concrete block variants, and a distribution policy. Mining uses the registered native blocks and preserves their ordinary mining requirements and drops.

## Context that code cannot infer

- Target pack: Rustic Craft 2 2.2.2, Minecraft 1.21.1, NeoForge 21.1.248.
- User requirement: all ores in the pack, including modded ores; quartz is the Nether material explicitly in scope for Chaos Depths. Netherrack is a separate optional block setting and off by default.
- User direction: no Netherrack placements by default; a proper operator setting may enable it later at an extremely rare rate.
- Common tags may omit modded ores; M0's artifact audit is part of the completeness proof.
- Ore rates affect server economy and require review; do not invent high rates.

## Scope

- Implement registry/tag-backed ore discovery and refresh behavior.
- Add explicit ID entries for audited ores missing from common tags.
- Map every ore to depth tiers and variants in a data-driven catalog.
- Populate M2 resource pockets using native ore blocks; include quartz in Chaos. Netherrack is separately governed by its disabled-by-default bounded option.
- Add server config `netherrack.enabled=false` and `netherrack.chunk_column_interval=256` with a hard valid range 256–65,536. At most one placement attempt is made per eligible 16×16 chunk column in the deepest tier, with one cluster of at most three blocks. Operators can only increase the interval (make it rarer); default runs place none.
- Add a completeness diagnostic that reports unassigned, missing, or unavailable entries.

## Non-goals

No new ore blocks, replacing loot tables, global worldgen modifications, artificial raw-ore mob rewards, or economy changes outside the mine.

## Dependencies and open decisions

- Depends on M0 pack ore audit and M2 region/resource-pocket placement.
- Product owner sets distribution weights from recorded playtest/economy evidence and documents the rationale; do not block routine tuning on extra approval.
- Product owner: Codex. Implementer: Devin. Reviewer: independent.

## Acceptance criteria

1. The runtime ore catalog reconciles against M0's exact pack audit; every known pack ore is assigned or the issue remains blocked with explicit missing IDs.
2. Each assigned ore appears in its intended depth tier; no ore is silently omitted due to tag reload or namespace differences.
3. Quartz appears in Chaos Depths and contained lava cannot leak outside mine-owned regions.
4. Default config yields zero Netherrack. If enabled, only the deepest configured tier is eligible; a seeded test proves no more than one attempt/cluster of at most three blocks per eligible chunk column and the configured interval is within 256–65,536. Operators cannot lower the interval or make it shallower; unsafe values are rejected with an admin-readable explanation.
5. Mining an ore uses that block's native hardness, harvest/tool rules, loot, and mod behavior.
6. Generated rates are documented and configurable; no unsupported claim of economic balance is made.
7. Tests cover tag reload, missing IDs, duplicate variants, tier assignment, Netherrack default/upper bounds, and generation restrictions.

## Verification

Run catalog comparison against the target dedicated server's live registry. Generate sample seeds for each tier, mine representative vanilla/modded/Nether ores with appropriate and inappropriate tools, and capture drops and rates.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals, affected ownership, dependencies, commands/results, and residual risks.
