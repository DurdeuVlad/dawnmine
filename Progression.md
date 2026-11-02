# Public Mine Gameplay Contract

## Player promise

The Public Mine is a shared daily expedition that teaches its upper depths, then asks players to adapt, prepare, and go farther. Each run offers useful choices among mining, combat, routes, hazards, and banking. Extracted resources persist as materials and equipment, so tomorrow's expedition begins with new capability. Deeper regions introduce new problems as earlier upgrades solve old ones. The deepest intended region is designed for coordinated teams, not one player kiting a large health bar.

The mine complements the existing factions mine; it does not replace or modify it.

## Core loop

1. **Prepare:** On the surface, players learn their current tools and equipment, see the next run/depth status, and choose to enter alone or with a group.
2. **Read the mine:** Generated routes, ore signatures, landmarks, encounter tells, and hazards give enough information to make a route choice. Branches trade safety, time, resources, and combat risk.
3. **Act with skill:** Mining rewards use of the appropriate tool and finding/working a deposit safely. Encounters combine verified Born in Chaos threats and mine hazards so movement, target choice, timing, and cooperation matter. Difficulty must not come only from inflated health or damage.
4. **Choose to bank or push:** Generated checkpoints clearly show the next route/depth and let players deposit into their private persistent Foreman's Dispatch. The more valuable branch can carry greater risk. Returning to the surface preserves banked items; the first version adds no custom death/item-loss penalty and keeps vanilla inventory behavior.
5. **Improve:** Players use persistent, banked resources in real Rustic Craft 2 recipes/equipment paths (or a bounded mine equipment path only when the pack lacks a suitable counter) to address a demonstrated earlier-depth problem.
6. **Adapt and descend:** The next region tests a different capability. Gear helps but does not erase encounter mechanics, route hazards, or the value of group coordination.
7. **Return tomorrow:** The shared terrain, mobs, loose drops, temporary route/checkpoint state, and run seed reset at the configured morning time. Carried equipment, player progress materials, and Dispatch contents persist.

## Progression rules

- A daily reset is a fresh expedition, not a player progression wipe. Durable capability comes from gear and materials players can keep or bank.
- Do not create a permanent depth-skip that makes the daily mine irrelevant. Shared checkpoints are usable by everyone after generation during that run; they expire on reset.
- Each upgrade needs an evidence-backed purpose: name the earlier problem it solves, the pack item/material and recipe that produce it, and the next problem that still demands different tactics or preparation.
- Audit the actual pack's ores, items, recipes, tools, armor, and existing progression before choosing any named tier. User examples (iron/steel, silver, diamond) are design cues, not permission to assume exact recipes or mod content.
- Mining, combat, and exploration must each produce a useful contribution to progression. No single activity should be mandatory for every player if a viable team role or alternate route can supply the same group need.
- Preserve native block mining requirements and native drops. Mine combat rewards are additional, bounded, and mine-only.
- Include all pack ores in the reviewed catalog. Chaos Depths can contain quartz and contained lava hazards. Netherrack generation is disabled by default; if enabled, allow at most one placement attempt per 256 eligible 16×16 chunk columns in the deepest configured region, with no more than one cluster of three blocks per eligible column. The operator may make it rarer (minimum interval 256, maximum interval 65,536); no config value may make it more common or shallower.
- The deepest intended content must have at least one encounter/objective whose mechanics and simultaneous demands make a coordinated group materially more effective than a solo player. Validate with real multiplayer playtests; configuration claims are insufficient.
- Preserve vanilla death and inventory rules in the first version. Create tension through the choice to continue or bank, encounter and environmental danger, and time/route tradeoffs. Do not silently invent an extra death penalty.

## Skill and fairness requirements

- Early depth is learnable and gives readable feedback before punishing mistakes.
- Escalation introduces different threat combinations, hazards, and decisions, not only larger numbers.
- The player can recognize important attack/hazard cues in the game interface/world before a reasonable reaction is required.
- Encounters challenge common low-effort tactics (including standing at safe range indefinitely) through verified mob abilities, pressure, route geometry, and bounded hazards; avoid unavoidable damage and unbounded mob accumulation.
- Every dangerous branch communicates a meaningful risk/reward signal before players commit.
- Checkpoints and storage make a failed push understandable and recovery possible without erasing previously banked progress.

## Admin and communication contract

- Provide an in-game `/publicmine help` entry point and discoverable command tree for setup, status, config inspection/change, validation, and safe recovery.
- Separate read-only status/help from mutating commands. Require an explicit operator permission threshold for setup, config writes, forced reset, and recovery. Check permission on the server for every invocation; console behavior must be intentional and documented.
- Configuration values are validated and bounded. Netherrack is off by default; operators can enable it, but cannot configure it above the very-rare/extreme-depth safety ceiling.
- Setup and validation report what is missing, how to fix it, and whether the mine is ready. Commands never claim success when the requested state was not persisted.
- Admin diagnostics go to the invoking admin/console and server log. Player messages are friendly, targeted, rate-limited, and contextual: for example, reset warnings go only to people inside the mine; do not broadcast stack traces or routine admin detail to everyone.
- Player-facing entry, checkpoint, bank, reset, and error feedback explains what happened and what action is available next.
- Admin help/onboarding must let a new operator get from an unconfigured server to a validated mine without reading source code.

## Evidence required to call it fun and ready

Use a repeatable playtest card for early, mid, and deepest intended regions. Record equipment/loadout, group size, route chosen, time to read/react, mining and combat contributions, deposit/return decision, death/recovery, resources banked, next upgrade enabled, and player observations. Compare solo and group results at the same deep encounter. The evidence must show:

- early players can understand core signals and make a successful useful run;
- each tested upgrade visibly changes what the group can handle;
- later tiers demand changed tactics or group composition;
- a coordinated group can progress where solo play is not a routine safe clear;
- mining, PvE, and exploration each feel worthwhile in the same economy;
- players choose to take another run or push deeper after a successful bank (record choices and short feedback; do not claim a subjective “addictive” result from code tests);
- daily reset, persistence, permissions, and targeted communication do not undermine that loop.

