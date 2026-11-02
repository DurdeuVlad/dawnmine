# Builder handoff assets — concept art

Annotated concept diagrams for the generated public-mine environment. These are
**handoff references for builders**, not schematics or structure files: the mine
is generated entirely from code and deterministic block layouts, so anything a
builder dresses on top must respect the generated geometry and registered
volumes shown here.

## Files

| Asset | Covers |
|---|---|
| `entrance-hub-concept.svg` | Vertical section of `publicmines:public_mine_v2` — bedrock shell, flat stone plain at Y 62, arrival pad + generated connector descent, the three depth regions, plus the overworld surface-depot exit pad |
| `safe-zone-concept.svg` | Plan + section of a checkpoint alcove — the 5×5×4 registered safe-zone volume, enforced server-side rules, and themed dressing guidance |

The product-owner concept boards (numbered AI-generated renders + builder
notes) live alongside at `docs/concept-art/public-mines/` —
`surface-entrance-hub.png` and `deep-checkpoint-safe-zone.png` are the visual
intent; these SVGs are the dimensional/rule annotations that ground them.

## Grounded in (verify before changing)

- `src/main/resources/data/publicmines/topology_profiles/standard.json` — regions,
  corridor half-width, node/cavern/checkpoint counts
- `docs/architecture/M2c-boundary.md` — bedrock shell: floor/roof on every chunk,
  walls on boundary chunks at X/Z ±1024
- `docs/architecture/M1-lifecycle-admin.md` — the generated 5×5 arrival pad and
  its owned volume (`MineManager.ARRIVAL`, arrival point [0,63,0])
- `docs/architecture/M2b-world-generation.md` — arrival connector,
  checkpoint pads (5×5, supported, 3-block clearance), Chaos palette, lava cups
- `docs/reports/M5a-checkpoint-travel-validation.md` — registered safe-zone
  volumes: arrival pad, checkpoint pads, single surface-exit pad
- `docs/issues/M2c-interaction-rules.md` — no-PvP, safe-zone PvE ban, ore-only
  mining, floor-torch-only placement

## What builders may and may not touch

- **Generated + reserved:** bedrock shell, the 5×5×4 safe-zone volumes (arrival
  pad, checkpoint pads, surface exit), carved routes/alcoves, ore pockets.
- **Dressable (non-binding):** thematic props around — never inside — the safe
  zones: Foreman's camp on the arrival plain, crates/boards/lantern posts on
  the cells surrounding a pad, signage, support beams.
- **Players may place** only allowed floor torches; only catalogued mine ores
  are breakable. Any authored dressing must not rely on players editing blocks.
