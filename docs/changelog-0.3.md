# Dawnmine 0.3.0 changelog

Breaking rename + whole-run prepared adventure.

- Renamed everything to Dawnmine: package `com.dwurdy.dawnmine`, mod id
  `dawnmine`, dimension `dawnmine:mine`, canonical command `/dawnmine`
  (no alias), saved data `dawnmine_run`/`dawnmine_dispatch`, config
  `dawnmine-server.toml`, artifact `dawnmine-0.3.0.jar`, author dwurdy.
- The mine is now fully generated before anyone is admitted: structural
  carves first, then dressing (supported floors, timber frames, rails,
  lamps), then the ore pass. Entry, checkpoint travel, login recovery and
  status all gate on complete preparation.
- Winding natural passages (interior halfwidth 3-6, height 4-8), connected
  lobed caverns (about 36+ blocks across, 16+ tall, standing pillars),
  mixed narrow mineshaft edges with timber supports every 7-9 blocks,
  rails, plank walkways, and warm lamps at junctions and checkpoints.
- Ore veins appear in exposed walls everywhere - the entrance descent,
  ordinary passages, rooms, and richer resource fields - with run
  provenance recorded for every placed block.
- Bounded asynchronous chunk requests (window of 8), ~4096 ops/tick work
  budget; generation pauses entirely while a daily reset is in progress
  and releases chunk requests on every terminal path.
- Encounters now spawn only when a player approaches a finished room.
- Migration: offline backup-first procedure in
  `docs/Dawnmine-0.3-upgrade.md`; `tools/dawnmine-0.3/copy-dispatch-data.py`
  copies Dispatch/config non-destructively.
