package com.dwurdy.dawnmine.cave;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where encounters wait, as a pure function of (stack, landmarks, run seed).
 * Sites are patrol spots on cave floors plus the encounter hooks of landmarks (nests and guardians).
 * Nothing is spawned here: the server wakes a site when a player comes near, once per run, so the
 * mine keeps no entities for places nobody visits and a reset needs no cleanup beyond the run's own.
 * Safe rooms (the Foreman's Office) never carry an encounter hook, and no patrol site lies near the hub.
 */
public final class EncounterSites {
    /** A place an encounter waits. {@code region} is the encounter-catalogue tier of its layer. */
    public record Site(String id, int layer, String region, int x, int y, int z, String kind) { }

    public static final int CELL = 28;
    public static final double CELL_CHANCE = 0.75;
    /** No patrol site within this Manhattan distance of the hub. */
    public static final int HUB_CLEARANCE = 80;
    private static final String[] REGIONS = {"upper", "middle", "chaos"};
    private static final int INDEX_CELL = 32;

    private final List<Site> sites = new ArrayList<>();
    private final Map<Long, List<Site>> index = new HashMap<>();

    public List<Site> sites() {
        return sites;
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

    public static EncounterSites plan(CaveStack stack, Landmarks landmarks, long seed) {
        EncounterSites out = new EncounterSites();
        for (int layer = 0; layer < stack.layers(); layer++) {
            CaveSheet sheet = stack.sheet(layer);
            for (int cz = Math.floorDiv(sheet.minZ(), CELL); cz <= Math.floorDiv(sheet.maxZ(), CELL); cz++) {
                for (int cx = Math.floorDiv(sheet.minX(), CELL); cx <= Math.floorDiv(sheet.maxX(), CELL); cx++) {
                    Rng rng = new Rng(seed ^ 0x7F4A7C159E3779B9L ^ (layer * 0x9E3779B97F4A7C15L)
                            ^ (cx * 0xC2B2AE3D27D4EB4FL) ^ (cz * 0x165667B19E3779F9L));
                    if (rng.nextDouble() >= CELL_CHANCE) {
                        continue;
                    }
                    for (int attempt = 0; attempt < 25; attempt++) {
                        int x = cx * CELL + rng.nextInt(CELL);
                        int z = cz * CELL + rng.nextInt(CELL);
                        if (sheet.isOpen(x, z) && sheet.heightAt(x, z) >= 4
                                && Math.abs(x) + Math.abs(z) >= HUB_CLEARANCE && !Rock.protectedColumn(x, z)) {
                            out.add(new Site(id(layer, x, z), layer, REGIONS[Math.min(layer, 2)],
                                    x, sheet.floorAt(x, z) + 1, z, "patrol"));
                            break;
                        }
                    }
                }
            }
        }
        for (Landmarks.Placement p : landmarks.placements()) {
            for (Landmarks.Hook h : p.hooks()) {
                if (h.kind().equals("encounter") && !p.safe()) {
                    out.add(new Site(id(p.layer(), h.x(), h.z()), p.layer(), REGIONS[Math.min(p.layer(), 2)],
                            h.x(), h.y(), h.z(), p.type().id));
                }
            }
        }
        return out;
    }

    /** Site ids use only letters, digits, ':' and '-' as the run's claim list requires. */
    static String id(int layer, int x, int z) {
        return "site:L" + layer + ":" + x + ":" + z;
    }

    private void add(Site s) {
        sites.add(s);
        index.computeIfAbsent(key(Math.floorDiv(s.x(), INDEX_CELL), Math.floorDiv(s.z(), INDEX_CELL)),
                k -> new ArrayList<>()).add(s);
    }

    private static long key(int gx, int gz) {
        return ((long) gx << 32) ^ (gz & 0xFFFFFFFFL);
    }
}
