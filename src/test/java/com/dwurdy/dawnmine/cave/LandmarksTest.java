package com.dwurdy.dawnmine.cave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.dawnmine.census.CensusAnalyzer;
import com.dwurdy.dawnmine.census.CensusReport;
import com.dwurdy.dawnmine.census.CensusSnapshot;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LandmarksTest {
    private static final CaveStack STACK = CaveStack.plan(64, 1);
    private static final Landmarks PLAN = Landmarks.plan(STACK, 1);

    @Test
    void sameSeedSamePlan() {
        Landmarks again = Landmarks.plan(STACK, 1);
        assertEquals(PLAN.placements(), again.placements());
        assertEquals(PLAN.palette(), again.palette());
        assertEquals(PLAN.columns().size(), again.columns().size());
        for (var e : PLAN.columns().entrySet()) {
            assertTrue(java.util.Arrays.equals(e.getValue(), again.columns().get(e.getKey())));
        }
        assertFalse(PLAN.placements().equals(Landmarks.plan(STACK, 2).placements()), "the seed changes the plan");
    }

    @Test
    void everyTypeIsPlacedOnEveryLayerInDensity() {
        Map<Landmarks.Type, Integer> perType = new EnumMap<>(Landmarks.Type.class);
        int[] perLayer = new int[3];
        for (Landmarks.Placement p : PLAN.placements()) {
            perType.merge(p.type(), 1, Integer::sum);
            perLayer[p.layer()]++;
        }
        System.out.println("  landmarks per type " + perType + " per layer " + java.util.Arrays.toString(perLayer));
        assertEquals(Landmarks.Type.values().length, perType.size(), "all types appear: " + perType);
        assertTrue(Landmarks.Type.values().length >= 8);
        for (int layer = 0; layer < 3; layer++) {
            assertTrue(perLayer[layer] > 300, "layer " + layer + " has " + perLayer[layer]);
        }
    }

    @Test
    void landmarksNeverOverlapAndKeepOutOfProtectedVolumes() {
        List<Landmarks.Placement> ps = PLAN.placements();
        for (int i = 0; i < ps.size(); i++) {
            Landmarks.Placement a = ps.get(i);
            assertFalse(Rock.protectedColumn(a.x(), a.z()), "anchor in protected volume");
            assertTrue(a.authoredOverride() == null, "no authored override yet");
            for (int j = i + 1; j < ps.size(); j++) {
                Landmarks.Placement b = ps.get(j);
                if (a.layer() == b.layer()) {
                    boolean apart = a.maxX() + 2 < b.minX() || b.maxX() + 2 < a.minX()
                            || a.maxZ() + 2 < b.minZ() || b.maxZ() + 2 < a.minZ();
                    assertTrue(apart, "overlap " + a + " " + b);
                }
            }
        }
        assertTrue(ps.stream().anyMatch(Landmarks.Placement::safe), "a Foreman's Office exists");
    }

    /** Every edited cell was solid rock: landmarks never open into caves or replace cave air. */
    @Test
    void editsOnlyTouchRock() {
        Rock rock = new Rock(STACK);
        for (var e : PLAN.columns().entrySet()) {
            int x = (int) (e.getKey() >> 32);
            int z = (int) (long) e.getKey();
            int[] c = e.getValue();
            for (int i = 0; i < c.length; i += 2) {
                assertTrue(rock.solid(x, c[i], z), "edit in open space or protected at " + x + "," + c[i] + "," + z);
            }
        }
    }

    /** The independent walker reaches every standable cell with the landmarks cut in. */
    @Test
    void landmarksKeepTheMineFullyWalkable() {
        CensusSnapshot snap = new CensusSnapshot(List.of(
                new CensusSnapshot.Band("L0", 24, 127),
                new CensusSnapshot.Band("L1", -30, 23),
                new CensusSnapshot.Band("L2", -64, -31)));
        STACK.addTo(snap);
        for (int x = -21; x <= 21; x++) {
            for (int z = -21; z <= 21; z++) {
                for (int y = 42; y <= 49; y++) {
                    snap.addOpen(x, y, z);
                }
            }
        }
        List<String> palette = PLAN.palette();
        for (var e : PLAN.columns().entrySet()) {
            int x = (int) (e.getKey() >> 32);
            int z = (int) (long) e.getKey();
            int[] c = e.getValue();
            for (int i = 0; i < c.length; i += 2) {
                String block = palette.get(c[i + 1]);
                if (block.equals("minecraft:air") || block.equals("minecraft:rail")) {
                    snap.addOpen(x, c[i], z);
                } else if (block.equals("minecraft:water")) {
                    snap.addFluid(x, c[i], z);
                }
            }
        }
        CensusReport r = CensusAnalyzer.analyze(snap, 0, 42, 8, CensusAnalyzer.Params.DEFAULT, List.of());
        assertTrue(r.startWalkable());
        assertEquals(0, r.unreachable(), "unreachable standable cells with landmarks: " + r.lines());
    }
}
