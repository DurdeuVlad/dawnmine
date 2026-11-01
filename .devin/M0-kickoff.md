# Devin kickoff — M0 only

Repository: rusticcraft-public-mines
First issue: M0 — Standalone mod foundation and pack compatibility (docs/issues/M0-foundation.md). GitHub issue: https://github.com/DurdeuVlad/rusticcraft-public-mines/issues/1

Use the installed Flux skills explicitly:

1. Invoke /devin:flux to compile the Goal/System/Constraints/Evaluation contract and select the smallest relevant skill set.
2. Use /devin:flux-discovery to inspect the local pack artifacts and environment; /devin:flux-research for NeoForge/Born in Chaos facts; /devin:flux-plan to set the bounded M0 steps.
3. Implement only M0 with /devin:flux-build and /devin:flux-code; use /devin:flux-architecture for the standalone mod boundary and server-only classloading.
4. Use /devin:flux-test and /devin:flux-verify for the requested build and dedicated-server evidence. User explicitly asked that this milestone be implemented and tested.
5. Ask a fresh independent reviewer to use /devin:flux-review, then invoke /devin:flux-evaluate before reporting done.

The scope and acceptance criteria are in the M0 issue. M0 is project scaffold and compatibility evidence only. Do not begin mine gameplay before reporting the M0 findings. Do not edit the existing KubeJS faction mine. The owner authorizes you to commit/push the M0 branch and merge its PR after every acceptance criterion passes, build and dedicated-server checks pass, a fresh independent flux-review passes, and flux-evaluate says complete. Do not merge on failed, blocked, or unsupported verification. Production release/deployment remains unauthorized.

## Product direction governing later milestones

The intended loop is a shared daily expedition: learn early depths, mine/fight/explore for useful resources, bank them safely, improve durable equipment between runs, then adapt to deeper threats. Gear solves demonstrated earlier-depth problems while later tiers introduce different readable tactical demands. The deepest intended content materially rewards coordinated teams. Mining, PvE, and exploration all contribute. The run resets daily, while equipment/progression materials and private Dispatch contents persist. Keep vanilla death/inventory behavior in the first version. Netherrack is off by default; if enabled later, configuration must constrain it to very rare extreme-depth generation. Admin onboarding/help/config/setup/status/validation and player messaging must be permission-aware, friendly, and targeted. Read `Progression.md` and the updated issues before work beyond M0. This is context only; M0 remains foundation and pack-audit work, with no gameplay implementation.

Work in a local issue branch or Devin session branch. Leave a reviewable working tree/branch and report changed files, build/server commands and results, exact artifact evidence, risks, and the next useful milestone decision.


