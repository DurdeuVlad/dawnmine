package com.dwurdy.dawnmine.cave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LootSitesTest {
    private static final CaveStack STACK = CaveStack.plan(64, 1);
    private static final Landmarks LANDMARKS = Landmarks.plan(STACK, 1);
    private static final LootSites SITES = LootSites.plan(STACK, LANDMARKS, 1);

    @Test
    void deterministicAndUniqueIds() {
        LootSites again = LootSites.plan(STACK, LANDMARKS, 1);
        assertEquals(SITES.sites(), again.sites());
        Set<String> ids = new HashSet<>();
        for (LootSites.Site s : SITES.sites()) {
            assertTrue(ids.add(s.id()), "duplicate site id " + s.id());
            assertTrue(s.id().matches("[A-Za-z0-9:_-]+") && s.id().length() <= 128, "id fits the claim list: " + s.id());
            assertTrue(s.kind().equals("crate") || s.kind().equals("locker"), "unknown kind " + s.kind());
        }
    }

    @Test
    void sitesStandOnOpenFloorsAwayFromTheHubAndEachHasAUniqueColumn() {
        int[] perLayer = new int[STACK.layers()];
        for (LootSites.Site s : SITES.sites()) {
            perLayer[s.layer()]++;
            CaveSheet sheet = STACK.sheet(s.layer());
            assertTrue(sheet.isOpen(s.x(), s.z()) && s.y() == sheet.floorAt(s.x(), s.z()) + 1, "loot site on the floor");
            assertTrue(Math.abs(s.x()) + Math.abs(s.z()) >= LootSites.HUB_CLEARANCE, "clear of the hub");
            assertFalse(Rock.protectedColumn(s.x(), s.z()), "not in the hub or forecourt");
            String[] regions = {"upper", "middle", "chaos"};
            assertEquals(regions[Math.min(s.layer(), regions.length - 1)], s.region());
            assertEquals(s, SITES.atColumn(s.layer(), s.x(), s.z()), "exact-column lookup must find its own site");
        }
        System.out.println("  loot sites per layer " + java.util.Arrays.toString(perLayer));
        for (int layer = 0; layer < STACK.layers(); layer++) {
            assertTrue(perLayer[layer] > 50, "layer " + layer + " has " + perLayer[layer]);
        }
    }

    /** Regression: different layers plan loot independently and can land on the same (x,z) -
     * the exact-column index must key on layer too, or one layer's site silently overwrites
     * another's and only one of them ever gets a container placed. */
    @Test
    void columnsSharedAcrossLayersDoNotOverwriteEachOther() {
        Map<String, LootSites.Site> byXZ = new HashMap<>();
        boolean foundSharedColumn = false;
        for (LootSites.Site s : SITES.sites()) {
            String xz = s.x() + ":" + s.z();
            LootSites.Site other = byXZ.putIfAbsent(xz, s);
            if (other != null && other.layer() != s.layer()) {
                foundSharedColumn = true;
                assertEquals(other, SITES.atColumn(other.layer(), other.x(), other.z()));
                assertEquals(s, SITES.atColumn(s.layer(), s.x(), s.z()));
            }
        }
        assertTrue(foundSharedColumn, "fixture seed never exercised a cross-layer shared column - "
                + "pick a different seed so this regression test actually tests something");
    }

    /** Review finding: a site chosen purely from open-floor/hub-clearance checks could land
     * inside a landmark's footprint and silently overwrite part of its authored content. */
    @Test
    void noSiteLandsInsideALandmarkFootprint() {
        for (LootSites.Site s : SITES.sites()) {
            for (Landmarks.Placement p : LANDMARKS.placements()) {
                if (p.layer() != s.layer()) continue;
                assertFalse(s.x() >= p.minX() && s.x() <= p.maxX() && s.z() >= p.minZ() && s.z() <= p.maxZ(),
                        "loot site " + s.id() + " lands inside landmark " + p.type() + " at " + p.x() + "," + p.z());
            }
        }
    }

    @Test
    void nearFindsOnlySitesInRange() {
        List<LootSites.Site> all = SITES.sites();
        LootSites.Site probe = all.get(all.size() / 2);
        List<LootSites.Site> near = SITES.near(probe.x(), probe.z(), 60);
        assertTrue(near.contains(probe));
        for (LootSites.Site s : near) {
            assertTrue(Math.abs(s.x() - probe.x()) <= 60 && Math.abs(s.z() - probe.z()) <= 60);
        }
        long expected = all.stream().filter(s -> Math.abs(s.x() - probe.x()) <= 60 && Math.abs(s.z() - probe.z()) <= 60).count();
        assertEquals(expected, near.size());
    }
}
