# Collaboration

## Ownership

- Product owner: Codex, as explicitly delegated by the server owner. Codex steers gameplay policy, supported pack version, configurable defaults, milestones, and acceptance of player-economy changes; record decisions in Decision.md and require evidence. Server operators can configure only the bounded settings exposed in game. The server owner retains production deployment authority, which is not granted by this document.
- Implementer: Devin, working one GitHub issue/milestone at a time and using the installed Flux skills listed in AGENTS.md.
- Planning and review support: Codex may prepare issue contracts, inspect changes, and give independent review. Devin's implementation claims require actual build/runtime evidence.

## Work flow

- Every implementation task starts from its linked GitHub issue and current milestone contract.
- Before editing, the implementer invokes Flux routing and uses the smallest relevant skill set. Non-trivial tasks include discovery, plan, implementation, verification, independent review, and evaluation.
- Each implementation is performed on an issue branch or local working branch. The PR body repeats intent, expectation, acceptance criteria, scope, non-goals, dependencies, test evidence, and unresolved risks.
- An independent reviewer examines behavior, tests, boundaries, and unintended effects before an issue is presented as complete.
- Codex owns product decisions and resolves product defaults from evidence without blocking on routine input; the recorded defaults are the 06:00 configured-timezone reset, shared generated checkpoints, and a private Dispatch of at least 54 slots in v1. Devin may resolve reversible implementation choices inside the accepted contract and should surface evidence that requires changing it; adjust defaults only when pack/runtime evidence demonstrates a problem.
- The owner has authorized issue-scoped implementation work to be committed, pushed, and merged on GitHub as it is completed. Merge only after acceptance criteria pass, required checks pass, an independent Flux review passes, and flux-evaluate returns complete. Do not merge failed, blocked, or unsupported verification. Production release and server deployment are not authorized.

## Communication and handoff

- GitHub issues are the source of task scope and acceptance evidence.
- Decision.md is authoritative for accepted choices and unresolved product decisions.
- Report blockers early. Do not convert missing pack/API evidence into a guessed implementation contract.
- Keep changes within the standalone mod. The KubeJS faction-mine system is an external compatibility surface and must remain untouched.

## Visibility and contributions

- Repository visibility: private.
- This project is not yet declared open source. Do not add a public contribution or support promise or choose a license without owner direction.

