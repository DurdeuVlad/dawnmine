# M4b combat reward validation

## Result

- `.\gradlew.bat test build` passed on 2026-11-02 (34 tasks; all project tests passed).
- Exact pack: NeoForge 21.1.248, Born in Chaos 1.7.6, 283 mods.
- Exact-pack opt-in command: `/publicmine_audit_encounters` with `-Dpublicmines.enableMineEncounterAudit=true`.
- Result: passed, 14 runtime cases. Evidence in `C:\m0s-audit\logs\latest.log` at 2026-11-02 18:15:25; tested artifact SHA-256 `64550E05022745BE205359B0B2C23A9D40DDD016929939ADD5C04AB2B396D9A1`.
- `/publicmine rewards` reported all three configured depth rates and purpose/sink metadata in the same pack.
- Test pack state restored after audit: the previous mod JAR hash and `server.properties` hash match their pre-test backups.

## Runtime cases

The audit passed existing M4a cases for missing entity rejection, infected ore tracking/reset cleanup, scheduled room spawn/replay, unsupported spawn surfaces, regional cap, and global cap. M4b cases passed for a configured upper-tier direct-player death callback, duplicate callback idempotency, environmental attribution exclusion, non-player attacker exclusion, tracked mob killed outside mine bounds exclusion, and untracked natural mob exclusion.

The 20% upper-tier reward is stochastic; the harness makes up to 64 independent eligible fixtures and observed one raw iron reward. The exact-pack audit invokes the mine listener directly with a synthetic `LivingDeathEvent` and player-attack `DamageSource`. It does not claim a real connected-player combat sequence or a runtime comparison of native loot. The implementation does not alter or cancel native entity loot; a connected-client death/drop comparison remains a playtest item.

## Reward progression evidence and limits

- Upper: `minecraft:raw_iron`, 20%, one item. M0 pack recipe audit identified the raw-iron → iron ingot → `magistuarmory:steel_ingot` route used by steel weapons.
- Middle: `werewolves:raw_silver`, 10%, one item. M0 pack audit identified smelting to silver ingots and silver weapon recipes.
- Chaos: `minecraft:diamond`, 2%, one item. M0 pack audit identified diamond equipment recipes. The exact contribution rate is intentionally provisional pending M8 group playtests.
- The bundled table is data-pack configurable; `/publicmine rewards` gives operators a compact rate and purpose summary.

The audit was performed in the disposable `publicmines-m4a-hook-callback-audit` world. After testing, the server was stopped, the original JAR and server properties were restored and their original SHA-256 hashes were confirmed.
