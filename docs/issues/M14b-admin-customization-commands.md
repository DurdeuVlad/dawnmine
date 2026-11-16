# M14b Issue Draft — Admin customization commands (ore, density, mobs, difficulty, loot)

## Planning metadata
- Issue type: Feature
- Priority: Medium
- Milestone: M14 — Per-layer customization and depth-based chaos escalation
- Assignee: Unassigned
- Target date: Not set

## Intent

The owner wants full in-game admin control over mine tuning - ore weight and vein size, ore
density, mob roster, mob difficulty, and loot contents - scoped per cave layer, without needing
to hand-edit JSON and trigger a datapack reload for every change. The owner explicitly chose the
in-game-command approach over a thinner "mostly hand-edit the files" alternative that was
proposed first in this planning conversation ("the commands are good, but... it is easier").

## Expectation

An admin (permission level 2, the existing `DawnmineCommand.ADMIN_LEVEL` threshold) can, entirely
through `/dawnmine config` subcommands with tab-completion for layers/blocks/entities/items:
- Inspect and override any ore's selection weight and vein-size range, per layer.
- Inspect and override ore density (veins per cell), per layer.
- Inspect and override the mob roster (which entities can appear) per layer.
- Inspect and override mob-difficulty-band stat multipliers (health/damage/speed), independent
  of layer, since those bands are level-based not depth-based.
- Inspect and override loot-table contents per layer.
- Save, reload, and show the full set of active overrides, with command feedback stating
  whether a given change needs a `/dawnmine reset` to appear in the world or applies
  immediately - reusing the existing `ConfigKeys.Timing` vocabulary (`IMMEDIATE`, `NEXT_RUN`,
  `NEXT_RESET`, `NEXT_RESTART` - `ConfigKeys.java:20-28`) rather than inventing new wording.

No change made through these commands is lost on a mod update: they persist to a separate
overrides store, never by mutating the shipped `ore_catalog`/`encounters`/`loot_table` files
directly.

## Context that code cannot infer

- `/dawnmine config get/set <key> <value>` already exists (`DawnmineCommand.java:86-98`,
  backed by `ConfigKeys.java`) for *scalar* NeoForge `ModConfigSpec` values - resetHour,
  netherrackEnabled, mineRegionRadiusChunks, etc. Each `ConfigKeys.Key` declares its own parser,
  bounds, example, and `Timing`. This is the right, already-proven mechanism for simple
  layer-independent or small-fixed-set scalars (e.g. a per-layer density *multiplier*, or a
  difficulty-band multiplier), and should be reused/extended rather than duplicated - see Scope.
- It is *not* a fit for the per-block/per-entity/per-item overrides this issue also needs: the
  set of ore block ids, mob entity ids, and loot item ids is open-ended and data-driven (loaded
  from `ore_catalog/standard.json`, `encounters/standard.json`, and the loot table JSON files via
  NeoForge `SimpleJsonResourceReloadListener`s), not a fixed schema `ModConfigSpec` can represent
  cleanly as individual keys. These need their own subcommand literals and their own persistence
  file.
- M14a's four real layer regions (upper/middle/chaos/nether) are a direct prerequisite - without
  them there is no nether layer to scope a command's `<layer>` argument against, and "nether
  mobs in the nether layer" cannot be expressed by these commands at all.
- Changes to ore weight/vein-size/density only visually take effect in the world after a
  `/dawnmine reset`, since chunks are baked into real blocks once generated (confirmed
  repeatedly in the M13 session - e.g. a noise-settings fix needed a full reset to actually
  change generated terrain). Mob-difficulty-band and loot-table-content changes take effect
  immediately for anything not yet spawned/opened: `MobLevelScaling.apply` runs at spawn time,
  and `MineLootManager.resolveNearby` resolves a container's loot table lazily, the first time a
  player is near enough for the chunk to load - so an edited table applies to every
  not-yet-opened container right after a reload, no reset needed. Mob *roster* changes (which
  entities an encounter composition can include) are closer to the loot-table case (affects
  not-yet-spawned sites immediately) but the implementer should confirm this against
  `MineEncounterManager.spawnSite`'s actual claim/caching behaviour rather than assume it.
- `OreRarity.java`'s hardcoded vein-size map is the real sizing authority today, separate from
  (and inconsistent with) the catalog's own unused `max_vein_size` field - this issue's
  vein-size override command needs a single clear target to write to; resolving that
  inconsistency is in scope here specifically because this issue cannot otherwise specify what
  "override vein size" means precisely (see Scope).

## Scope

- Design and implement a persisted overrides store - a new `dawnmine_overrides.json` (or
  equivalent) under the server's mod-data directory, distinct from any shipped datapack file -
  holding: per-layer per-block ore weight/vein-size overrides, per-layer density overrides,
  per-layer mob-roster add/remove lists, per-band difficulty multiplier overrides, and per-layer
  loot-entry add/remove overrides. Loaded once at startup and merged on top of the shipped base
  data at the point each system currently reads it (`OreCatalog`, `EncounterSites`/
  `MineEncounterCatalog` resolution, `OreField`'s density constant, `MobLevelScaling`, and
  whatever currently supplies loot-table contents to `MineLootManager`).
- As part of the above, resolve the `OreRarity`/catalog `max_vein_size` inconsistency flagged in
  M14a's non-goals and M12a's own history: pick one authoritative place vein-size overrides are
  read from and written to, and say so explicitly in the PR.
- Add the following subcommand literals under `/dawnmine config` (all gated by `ADMIN_LEVEL`,
  all with tab-completion from the relevant registry/catalog where applicable):
  - `/dawnmine config ore <layer> list|set <block> weight <n>|set <block> veinsize <min> <max>|reset <block>`
  - `/dawnmine config density <layer> get|set <veinsPerCell>|reset`
  - `/dawnmine config mobs <layer> list|add <entity>|remove <entity>|reset`
  - `/dawnmine config difficulty list|<band> set health|damage|speed <multiplier>|<band> reset`
  - `/dawnmine config loot <layer> list|add <item> <weight> <min> <max>|remove <item>|reset`
  - `/dawnmine config save` / `/dawnmine config reload` / `/dawnmine config show`
  `<layer>` is `upper|middle|chaos|nether` throughout, consistent with M14a's region names.
- Generalize `MobLevelScaling`'s three hardcoded bands (`MobLevelScaling.java`, bands
  3-5/6-8/9+) into a small loadable list of {minLevel, maxLevel, healthBonus, damageBonus,
  speedBonus, color} entries, since there is currently no data structure for the `difficulty`
  commands to read or write - this is real refactor work, not just wiring a command to an
  existing field.
- Every mutating command replies with which `ConfigKeys.Timing`-equivalent applies to that
  change (immediate vs. needs-reset), reusing that existing vocabulary.
- Validate every write with the same bounds the existing data loaders already enforce (e.g.
  `OreCatalog.Entry`'s `weight` 1-1000 bound, `MineEncounterCatalog`'s population caps) so a bad
  command cannot produce a value the loader itself would have rejected from JSON.

## Non-goals

- Changing the shipped base `ore_catalog`/`encounters`/`loot_table` files from these commands -
  they remain the untouched, reviewable default; overrides always live separately.
- World-geometry customization (layer extents, descent distances, cave shape) - explicitly
  called out as a separate, more guarded, later piece in this conversation's own brainstorm; not
  part of this issue.
- Systematic depth-based escalation logic itself (that's M14c) - this issue only needs to expose
  *a* density/difficulty knob per layer for M14c to plug into later; it does not decide how
  escalation should work.
- A GUI or web-based config editor - command-line only, per the owner's explicit preference.

## Dependencies and open decisions

- Depends on M14a (four real layer regions) - cannot be implemented first, since there is
  nowhere for a `<layer>` argument to resolve `"nether"` to otherwise.
- Depends on M12a's existing ore-rarity research being the baseline the `veinsize` override
  command edits (not invalidated by it) - the override is a per-admin deviation from M12a's
  researched defaults, not a replacement for that research.
- Open decision: exact on-disk format and location for the overrides store (JSON vs. a second
  NeoForge `ModConfigSpec` section; per-world vs. per-server-install). Needs resolving by the
  implementer, with the "separate from shipped data, survives mod updates" constraint as the
  hard requirement either way.
- Open decision: whether mob-roster changes apply immediately or need a reset - flagged above as
  needing confirmation against `MineEncounterManager.spawnSite`'s actual behaviour before the
  command's feedback message can be written accurately.
- Product owner: Durdeu Vlad-Ioan. Implementer: the next agent assigned this issue. Reviewer:
  independent.

## Acceptance criteria

1. Every listed `/dawnmine config` subcommand is implemented, permission-gated at
   `ADMIN_LEVEL`, and tab-completes its `<layer>`/`<block>`/`<entity>`/`<item>`/`<band>`
   argument from the relevant live registry or catalog.
2. A `set` followed by `save` persists to the overrides store and survives a server restart
   without needing a `/dawnmine reset`; a `reset` for a given entry removes it from the
   overrides store and the shipped base value takes over again, confirmed by `config show`.
3. `config reload` discards any unsaved in-memory change and reloads from disk.
4. Every mutating command's reply states correctly, and matches actual observed behaviour
   (verified, not just asserted), whether the change is immediate or needs `/dawnmine reset`.
5. An out-of-bounds or malformed value (bad block id, weight outside 1-1000, negative vein size,
   etc.) is rejected with the same friendly error style `ConfigKeys.set` already uses, never a
   raw exception or a value the data loader would itself have rejected.
6. `MobLevelScaling`'s bands are generalized into a loadable list and the `difficulty` command
   round-trips through it correctly (set, verified via `config show`, then observed on a
   newly-spawned mob at that level).
7. A GameTest or unit test exists for the overrides-merge logic (base + override = effective
   value) for at least one case in each of ore/density/mobs/difficulty/loot.

## Verification

`./gradlew test` full suite plus new coverage for the overrides-merge logic and the
`MobLevelScaling` band generalization. Manual command-by-command walkthrough on the local test
server: set an override, confirm `config show` reflects it, confirm the stated
immediate-vs-reset-required timing is actually correct by observing the live world, restart the
server and confirm persistence, then `reset` the override and confirm the base value returns.

## PR contract

Repeat intent, expectation, acceptance criteria with evidence, non-code context, scope/non-goals,
affected ownership, dependencies, commands/results, and residual risks - including the resolved
overrides-store format decision and the confirmed (not assumed) immediate-vs-reset timing for
mob-roster changes.
