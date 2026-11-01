# Devin kickoff — M0 only

Repository: rusticcraft-public-mines
First issue: M0 — Standalone mod foundation and pack compatibility (see docs/issues/M0-foundation.md and GitHub issue linked from README after creation).

Use the installed Flux skills explicitly:

1. Invoke /devin:flux to compile the Goal/System/Constraints/Evaluation contract and select the smallest relevant skill set.
2. Use /devin:flux-discovery to inspect the local pack artifacts and environment; /devin:flux-research for NeoForge/Born in Chaos facts; /devin:flux-plan to set the bounded M0 steps.
3. Implement only M0 with /devin:flux-build and /devin:flux-code; use /devin:flux-architecture for the standalone mod boundary and server-only classloading.
4. Use /devin:flux-test and /devin:flux-verify for the requested build and dedicated-server evidence. User explicitly asked that this milestone be implemented and tested.
5. Ask a fresh independent reviewer to use /devin:flux-review, then invoke /devin:flux-evaluate before reporting done.

The scope and acceptance criteria are in the M0 issue. M0 is project scaffold and compatibility evidence only. Do not begin mine gameplay before reporting the M0 findings. Do not edit the existing KubeJS faction mine. Do not commit, push, merge, publish, or deploy implementation changes.

Work in a local issue branch or Devin session branch. Leave a reviewable working tree/branch and report changed files, build/server commands and results, exact artifact evidence, risks, and the next useful milestone decision.
