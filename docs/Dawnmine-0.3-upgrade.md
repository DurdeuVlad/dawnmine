# Dawnmine 0.3 Upgrade Guide (breaking change)

0.3 renames the mod to **Dawnmine** (`com.dwurdy.dawnmine`, mod id
`dawnmine`, dimension `dawnmine:mine`, command `/dawnmine` - no
`/publicmine` alias) and replaces the explore-as-you-go generator with a
fully pre-generated cave/mineshaft adventure. **This is a breaking
migration**: 0.2 worlds do NOT carry over their run receipt, and the old
dimension and saved data are never modified.

## What carries over / what does not

- **Carries over**: player inventories, Foreman's Dispatch contents, the
  overworld, all other dimensions, and every old file on disk.
- **Does not carry over**: the 0.2 run record (`publicmines_run.dat`),
  checkpoint stations, and the old dimension `publicmines:public_mine_v2`.
  Run metadata intentionally resets; the new run is generated fresh.
  `publicmines_run.dat` is never read by 0.3 and stays on disk untouched.

## Offline upgrade procedure (server STOPPED)

1. **Back up the whole server directory** (or at least `world/`).
2. While still on the 0.2 build: evacuate every player from the public
   mine (`/publicmine leave`) and have them bank what they want to keep in
   their Foreman's Dispatch (it is private and persistent).
3. Stop the server. Remove `mods/publicmines-*.jar` manually.
4. Install `mods/dawnmine-0.3.0.jar` and verify its `.sha256`.
5. Copy the durable shared data (old files are preserved, never
   overwritten):

   ```bash
   python tools/dawnmine-0.3/copy-dispatch-data.py <path-to-world>
   ```

6. Start the server and run `/dawnmine setup guide` → `setup validate` →
   `setup initialize`. The whole run is then prepared automatically;
   `/dawnmine status` shows progress and entry opens when every segment is
   generated.
7. The old dimension folder (`world/dimensions/publicmines/`) and
   `publicmines_run.dat` may be left in place (harmless) or archived by an
   admin manually - never delete player data.

## Rollback

The only supported rollback is restoring the full pre-upgrade backup.
Returning a stale 0.2 jar without the matching backup can duplicate or lose
post-upgrade Dispatch transactions. Do not delete the new data as a rollback.

Evacuate **online and offline** players from the old mine before upgrading:
players offline inside `publicmines:public_mine_v2` must log in and leave on
0.2 first. This mod never rewrites player NBT.
