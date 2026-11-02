# M5b Foreman's Dispatch — ownership and lifecycle boundaries

## Decision

Personal Dispatch contents live in `DispatchData`, a `SavedData` record named
`publicmines_dispatch` stored on the **overworld `DimensionDataStorage`**. The
locker key is the stable player `UUID`; the record is deliberately independent
of `MineRunData` so a daily reseed — which calls `beginNewRun` and clears
run-owned volumes, zones, stations, and reward claims — can never erase or
re-key a player's bank.

## Boundaries

- **Ownership:** the UUID is the sole boundary. `DispatchContainer` is a live
  view over one owner's `List<ItemStack>` of exactly `SLOT_COUNT = 54` slots;
  `DispatchMenu.stillValid` requires the same UUID. Other players get an
  independent container and can neither read nor mutate a foreign locker.
- **Access policy:** `DispatchManager.isAuthorizedAtStation` only authorizes
  inside the recorded `surface_exit` safe zone (overworld) or a registered
  checkpoint `OwnedVolume` (mine dimension), and only while the run is `READY`.
  Stations are the M5a 5×5×4 generated pads — no arbitrary-coordinate access
  and no dispatch block.
- **Sessions:** `activeSessions` (per-UUID) prevents one player stacking two
  Dispatch menus; different owners run concurrently. The lock is released by
  `DispatchMenu.removed`, by the `PlayerLoggedOutEvent` hook, and reconciled
  every server tick against actually-open menus, because vanilla never invokes
  `removed` on an abrupt disconnect (which would otherwise leak the lock and
  drop the cursor stack).
- **Integrity:** schema 1, ≤10,000 lockers, per-slot `{slot, item}` NBT via
  registry-aware `ItemStack.save/parse`. Any malformed or future-version data
  fails closed into `unsupportedData` — preserved verbatim, never silently
  deleted — and the UI reports an administrator-facing error instead of
  wiping the bank.

## Rejected alternatives

- Player NBT / capability storage: would entangle the locker with the player
  record, complicate admin inspection, and still need a same-contents server
  lookup; `SavedData` is the established run-data pattern in this codebase.
- Mine-owned (`MineRunData`) storage: would violate the product rule that
  Dispatch contents survive daily resets.
- Vanilla Ender Chest: contract requires a separate, larger (54-slot) private
  store with terminal-gated access.

## Deferred

Capacity upgrades, shared/team vaults, sorting/filters, and any reset of
personal storage are explicit non-goals for v1. M6 consumes banked materials
for the progression economy; M7 owns the real daily-reset trigger.
