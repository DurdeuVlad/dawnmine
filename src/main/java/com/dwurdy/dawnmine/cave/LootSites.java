package com.dwurdy.dawnmine.cave;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M12d: where loot containers wait, as a pure function of (stack, run seed). Mirrors
 * {@link EncounterSites} exactly - a loot site is a planned position only; nothing is placed
 * here. The container is placed and its loot table attached the first time a player comes near
 * (see the loot manager), so a site nobody visits costs nothing and a reset needs no cleanup
 * beyond the run's own claim set.
 */
public final class LootSites {
    /** A place a loot container waits. {@code kind} selects which loot table it gets. */
    public record Site(String id, int layer, String region, int x, int y, int z, String kind) { }

    public static final int CELL = 36;
    public static final double CELL_CHANCE = 0.7;
    /** No loot site within this Manhattan distance of the hub (same clearance as encounters). */
    public static final int HUB_CLEARANCE = 80;
    /** One in this many sites is a small "vault" cluster (a few boxes together, guarded) instead
     * of a single container. */
    private static final int VAULT_EVERY = 5;
    private static final int VAULT_EXTRA_BOXES = 2;
    private static final String[] REGIONS = {"upper", "middle", "chaos"};
    private static final int INDEX_CELL = 48;
    /** Alternates crate/locker so a region's loot doesn't read as one repeated prop. */
    private static final String[] KINDS = {"crate", "locker"};

    private final List<Site> sites = new ArrayList<>();
    private final Map<Long, List<Site>> index = new HashMap<>();
    private final Map<String, Site> byColumn = new HashMap<>();
    private final java.util.Set<String> guardianAnchors = new java.util.HashSet<>();

    public List<Site> sites() {
        return sites;
    }

    /** True for the one site in a vault cluster that should also spawn its guard, the first time
     * it resolves. */
    public boolean isGuardianAnchor(String siteId) {
        return guardianAnchors.contains(siteId);
    }

    /** Exact-column lookup for the generation pass, keyed by layer too: different layers plan
     * independently and can land on the same (x,z) with nothing to stop it, so the column key
     * alone is not unique across the whole stack. */
    public Site atColumn(int layer, int x, int z) {
        return byColumn.get(columnKey(layer, x, z));
    }

    private static String columnKey(int layer, int x, int z) {
        return layer + ":" + x + ":" + z;
    }

    /** Sites whose centre lies within {@code radius} (Chebyshev, x/z) of a point. */
    public List<Site> near(int x, int z, int radius) {
        List<Site> out = new ArrayList<>();
        for (int gz = Math.floorDiv(z - radius, INDEX_CELL); gz <= Math.floorDiv(z + radius, INDEX_CELL); gz++) {
            for (int gx = Math.floorDiv(x - radius, INDEX_CELL); gx <= Math.floorDiv(x + radius, INDEX_CELL); gx++) {
                for (Site s : index.getOrDefault(key(gx, gz), List.of())) {
                    if (Math.abs(s.x() - x) <= radius && Math.abs(s.z() - z) <= radius) {
                        out.add(s);
                    }
                }
            }
        }
        return out;
    }

    public static LootSites plan(CaveStack stack, Landmarks landmarks, long seed) {
        LootSites out = new LootSites();
        for (int layer = 0; layer < stack.layers(); layer++) {
            CaveSheet sheet = stack.sheet(layer);
            for (int cz = Math.floorDiv(sheet.minZ(), CELL); cz <= Math.floorDiv(sheet.maxZ(), CELL); cz++) {
                for (int cx = Math.floorDiv(sheet.minX(), CELL); cx <= Math.floorDiv(sheet.maxX(), CELL); cx++) {
                    Rng rng = new Rng(seed ^ 0x100716D0071E1DL ^ (layer * 0x9E3779B97F4A7C15L)
                            ^ (cx * 0xC2B2AE3D27D4EB4FL) ^ (cz * 0x165667B19E3779F9L));
                    if (rng.nextDouble() >= CELL_CHANCE) {
                        continue;
                    }
                    for (int attempt = 0; attempt < 25; attempt++) {
                        int x = cx * CELL + rng.nextInt(CELL);
                        int z = cz * CELL + rng.nextInt(CELL);
                        if (sheet.isOpen(x, z) && sheet.heightAt(x, z) >= 3
                                && Math.abs(x) + Math.abs(z) >= HUB_CLEARANCE && !Rock.protectedColumn(x, z)
                                && !insideAnyLandmark(landmarks, layer, x, z)
                                && !out.byColumn.containsKey(columnKey(layer, x, z))) {
                            String region = REGIONS[Math.min(layer, REGIONS.length - 1)];
                            String kind = KINDS[rng.nextInt(KINDS.length)];
                            String anchorId = id(layer, x, z);
                            out.add(new Site(anchorId, layer, region, x, sheet.floorAt(x, z) + 1, z, kind));
                            boolean vault = out.sites.size() % VAULT_EVERY == 0;
                            if (vault) {
                                out.guardianAnchors.add(anchorId);
                                placeVaultExtras(out, sheet, landmarks, rng, layer, region, x, z);
                            }
                            break;
                        }
                    }
                }
            }
        }
        return out;
    }

    /** A vault's extra boxes: a couple more containers near the anchor, same layer and region, so
     * a guarded site reads as a small stash rather than one more single crate. Best-effort - a
     * vault with no room nearby simply keeps its one anchor box. */
    private static void placeVaultExtras(LootSites out, CaveSheet sheet, Landmarks landmarks, Rng rng,
            int layer, String region, int ax, int az) {
        int placed = 0;
        for (int attempt = 0; attempt < 30 && placed < VAULT_EXTRA_BOXES; attempt++) {
            int x = ax + rng.nextInt(9) - 4;
            int z = az + rng.nextInt(9) - 4;
            if ((x == ax && z == az) || !sheet.isOpen(x, z) || sheet.heightAt(x, z) < 3
                    || Math.abs(x) + Math.abs(z) < HUB_CLEARANCE || Rock.protectedColumn(x, z)
                    || insideAnyLandmark(landmarks, layer, x, z)
                    || out.byColumn.containsKey(columnKey(layer, x, z))) {
                continue;
            }
            String kind = KINDS[rng.nextInt(KINDS.length)];
            out.add(new Site(id(layer, x, z), layer, region, x, sheet.floorAt(x, z) + 1, z, kind));
            placed++;
        }
    }

    /** Review finding (M12d): a loot site chosen purely from open-floor/hub-clearance checks could
     * land inside a landmark's own footprint and have its placed chest silently overwrite part of
     * that landmark's authored content, with nothing to detect it. Landmarks are placed first in
     * {@code CaveTerrain.carve}, so this check - not write order - is what actually prevents it. */
    private static boolean insideAnyLandmark(Landmarks landmarks, int layer, int x, int z) {
        for (Landmarks.Placement p : landmarks.placements()) {
            if (p.layer() == layer && x >= p.minX() && x <= p.maxX() && z >= p.minZ() && z <= p.maxZ()) {
                return true;
            }
        }
        return false;
    }

    /** Site ids use only letters, digits, ':' and '-', same claim-list requirement as encounters. */
    static String id(int layer, int x, int z) {
        return "loot:L" + layer + ":" + x + ":" + z;
    }

    private void add(Site s) {
        sites.add(s);
        index.computeIfAbsent(key(Math.floorDiv(s.x(), INDEX_CELL), Math.floorDiv(s.z(), INDEX_CELL)),
                k -> new ArrayList<>()).add(s);
        byColumn.put(columnKey(s.layer(), s.x(), s.z()), s);
    }

    private static long key(int gx, int gz) {
        return ((long) gx << 32) ^ (gz & 0xFFFFFFFFL);
    }
}
