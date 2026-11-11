package com.dwurdy.dawnmine.cave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.dawnmine.census.CensusAnalyzer;
import com.dwurdy.dawnmine.census.CensusReport;
import com.dwurdy.dawnmine.census.CensusSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CavePlannerTest {
    private static final CavePlanner.Spec SPEC = CavePlanner.Spec.standard().withRadius(300);

    private static boolean same(CaveSheet a, CaveSheet b) {
        for (int z = a.minZ(); z <= a.maxZ(); z++) {
            for (int x = a.minX(); x <= a.maxX(); x++) {
                if (a.floorAt(x, z) != b.floorAt(x, z) || a.heightAt(x, z) != b.heightAt(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Test
    void sameSeedGivesSameSheetAndDifferentSeedsDiffer() {
        CaveSheet a = CavePlanner.plan(SPEC, 42);
        assertTrue(same(a, CavePlanner.plan(SPEC, 42)));
        assertFalse(same(a, CavePlanner.plan(SPEC, 43)));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5})
    void floorsAreWalkableHeadroomHoldsAndHubIsClosed(long seed) {
        CaveSheet s = CavePlanner.plan(SPEC, seed);
        for (int z = s.minZ(); z <= s.maxZ(); z++) {
            for (int x = s.minX(); x <= s.maxX(); x++) {
                int h = s.heightAt(x, z);
                assertTrue(h == 0 || h >= CaveSheet.MIN_HEIGHT, "headroom at " + x + "," + z);
                if (h > 0) {
                    assertTrue(s.ceilingAt(x, z) <= SPEC.bandMaxY(), "ceiling inside the band");
                    assertTrue(s.floorAt(x, z) >= SPEC.bandMinY(), "floor inside the band");
                }
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (s.contains(x + dx, z + dz)) {
                            assertTrue(Math.abs(s.floorAt(x, z) - s.floorAt(x + dx, z + dz)) <= 1,
                                    "floor step at " + x + "," + z);
                        }
                    }
                }
                if (Math.abs(x) <= 24 && Math.abs(z) <= 24) {
                    assertEquals(0, h, "hub box must stay solid at " + x + "," + z);
                }
            }
        }
        assertEquals(41, s.floorAt(36, 0));
        assertTrue(s.heightAt(36, 0) >= CaveSheet.MIN_HEIGHT);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5})
    void everyOpenColumnIsOneRegionWithTheForecourt(long seed) {
        CaveSheet s = CavePlanner.plan(SPEC, seed);
        boolean[] seen = new boolean[s.sizeX() * s.sizeZ()];
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        queue.add(new int[] {36, 0});
        seen[s.index(36, 0)] = true;
        int reached = 1;
        int[][] d4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            for (int[] d : d4) {
                int nx = c[0] + d[0];
                int nz = c[1] + d[1];
                if (s.isOpen(nx, nz) && !seen[s.index(nx, nz)]) {
                    seen[s.index(nx, nz)] = true;
                    reached++;
                    queue.add(new int[] {nx, nz});
                }
            }
        }
        assertEquals(s.openColumns(), reached, "open columns outside the forecourt region");
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5})
    void openShareIsCaveLikeNotEmptyNotSolid(long seed) {
        CaveSheet s = CavePlanner.plan(SPEC, seed);
        double share = s.openColumns() / (double) (s.sizeX() * s.sizeZ());
        System.out.printf("seed %d: open share %.3f%n", seed, share);
        assertTrue(share > 0.08 && share < 0.60, "open share " + share);
    }

    /** Independent proof: the census walker reaches every standable cell of the planned cave. */
    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3})
    void censusWalkerReachesEveryStandableCell(long seed) {
        CaveSheet s = CavePlanner.plan(SPEC, seed);
        CensusSnapshot snap = new CensusSnapshot(List.of(new CensusSnapshot.Band("layer", 0, 200)));
        s.addTo(snap);
        CensusReport r = CensusAnalyzer.analyze(snap, 36, 42, 0, CensusAnalyzer.Params.DEFAULT);
        System.out.printf("seed %d: standable=%d reachable=%d unreachable=%d routeSteps=%d%n",
                seed, r.standable(), r.reachable(), r.unreachable(), r.routeSteps());
        assertTrue(r.startWalkable());
        assertTrue(r.standable() > 1000);
        assertEquals(0, r.unreachable(), "standable cells the walker cannot reach");
    }
    /** The real mine-sized layer: plan time is bounded and the whole layer is one walkable region. */
    @Test
    void fullSizeLayerPlansQuicklyAndIsFullyWalkable() {
        long t0 = System.nanoTime();
        CaveSheet s = CavePlanner.plan(CavePlanner.Spec.standard(), 11);
        long planMs = (System.nanoTime() - t0) / 1_000_000;
        CensusSnapshot snap = new CensusSnapshot(List.of(new CensusSnapshot.Band("layer", 0, 200)));
        s.addTo(snap);
        CensusReport r = CensusAnalyzer.analyze(snap, 36, 42, 0, CensusAnalyzer.Params.DEFAULT);
        System.out.printf("full layer: planMs=%d openColumns=%d standable=%d unreachable=%d%n",
                planMs, s.openColumns(), r.standable(), r.unreachable());
        assertTrue(planMs < 20_000, "planning took " + planMs + " ms");
        assertEquals(0, r.unreachable());
        assertTrue(r.standable() > 500_000);
    }
}
