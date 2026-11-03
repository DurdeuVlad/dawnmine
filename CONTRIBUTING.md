# Contributing to Dawnmine

Thanks for helping make the shared mine better.

## Ground rules

- **Server-side only.** Dawnmine runs entirely on the dedicated server; never introduce client-only class loads.
- **Mine-owned state only.** The mod must never modify other dimensions, the factions mine, vanilla loot/recipes globally, or player progression outside the mine.
- **Bounded changes.** Match the existing milestone/issue-contract style (`docs/issues/`): state the problem, the acceptance criteria, and the evidence that proves it.
- **No invented pack facts.** Item/recipe/entity claims about Rustic Craft 2 must be verified against the actual pack (see `docs/reports/` for how previous audits did it).

## Build & test

Requires Java 21.

```bat
gradlew.bat build            # compiles + runs JUnit tests, produces build/libs/publicmines-<version>.jar
gradlew.bat test             # JUnit only
gradlew.bat runGameTestServer  # in-game test suite (dimension-dependent tests skip in the flat test world)
gradlew.bat runServer        # local dedicated dev server
```

Full acceptance runs GameTests on the real Rustic Craft 2 2.2.2 pack inside the generated `public_mine_v2` dimension — see `docs/reports/M8-playtest-validation.md` for the exact recipe.

## Pull requests

- One bounded change per PR, with the issue or motivation referenced.
- `gradlew.bat build` must be green; GameTests must not regress (dimension-dependent skips are expected in the flat world, not on the pack).
- Non-trivial changes get an independent review before merge — see `AGENTS.md` and `Collaboration.md` for the review/evaluation expectations this project uses.
- Don't commit secrets, world saves, or local environment paths; `.devin/` is internal workspace state and is gitignored.

## Reporting bugs / requesting features

Use the issue templates — include the mod + pack versions (`publicmines-<v>` on RC2 `2.2.2`), what you ran (`/publicmine status`, `setup validate` output helps), expected vs actual, and the relevant `latest.log` excerpt.
