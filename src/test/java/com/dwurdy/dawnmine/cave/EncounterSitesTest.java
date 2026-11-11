package com.dwurdy.dawnmine.cave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EncounterSitesTest {
    private static final CaveStack STACK = CaveStack.plan(64, 1);
    private static final Landmarks LANDMARKS = Landmarks.plan(STACK, 1);
    private static final EncounterSites SITES = EncounterSites.plan(STACK, LANDMARKS, 1);

    @Test
    void deterministicAndUniqueIds() {
        EncounterSites again = EncounterSites.plan(STACK, LANDMARKS, 1);
        assertEquals(SITES.sites(), again.sites());
        Set<String> ids = new HashSet<>();
        for (EncounterSites.Site s : SITES.sites()) {
            assertTrue(ids.add(s.id()), "duplicate site id " + s.id());
            assertTrue(s.id().matches("[A-Za-z0-9:_-]+") && s.id().length() <= 128, "id fits the claim list: " + s.id());
        }
    }

    @Test
    void sitesStandOnCaveFloorsAwayFromTheHubAndSafeRooms() {
        int[] perLayer = new int[3];
        for (EncounterSites.Site s : SITES.sites()) {
            perLayer[s.layer()]++;
            CaveSheet sheet = STACK.sheet(s.layer());
            if (s.kind().equals("patrol")) {
                assertTrue(sheet.isOpen(s.x(), s.z()) && s.y() == sheet.floorAt(s.x(), s.z()) + 1, "patrol on the floor");
                assertTrue(Math.abs(s.x()) + Math.abs(s.z()) >= EncounterSites.HUB_CLEARANCE, "clear of the hub");
            }
            assertFalse(Rock.protectedColumn(s.x(), s.z()), "not in the hub or forecourt");
            assertEquals(new String[] {"upper", "middle", "chaos"}[s.layer()], s.region());
        }
        System.out.println("  encounter sites per layer " + java.util.Arrays.toString(perLayer));
        for (int layer = 0; layer < 3; layer++) {
            assertTrue(perLayer[layer] > 500, "layer " + layer + " has " + perLayer[layer]);
        }
        for (Landmarks.Placement p : LANDMARKS.placements()) {
            if (p.safe()) {
                for (EncounterSites.Site s : SITES.sites()) {
                    assertFalse(s.x() >= p.minX() && s.x() <= p.maxX() && s.z() >= p.minZ() && s.z() <= p.maxZ()
                            && s.layer() == p.layer() && !s.kind().equals("patrol"), "encounter inside a safe room");
                }
            }
        }
    }

    @Test
    void nearFindsOnlySitesInRange() {
        List<EncounterSites.Site> all = SITES.sites();
        EncounterSites.Site probe = all.get(all.size() / 2);
        List<EncounterSites.Site> near = SITES.near(probe.x(), probe.z(), 40);
        assertTrue(near.contains(probe));
        for (EncounterSites.Site s : near) {
            assertTrue(Math.abs(s.x() - probe.x()) <= 40 && Math.abs(s.z() - probe.z()) <= 40);
        }
        long expected = all.stream().filter(s -> Math.abs(s.x() - probe.x()) <= 40 && Math.abs(s.z() - probe.z()) <= 40).count();
        assertEquals(expected, near.size());
    }
}
