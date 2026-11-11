package com.dwurdy.dawnmine.census;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CensusAnalyzerTest {

    /** Corridor x0..20 at y10 (2 high), stairs down to y7 at x23, one isolated trap,
     * one reachable ore, one buried ore, one mob. */
    private static CensusSnapshot fixture() {
        CensusSnapshot s = new CensusSnapshot(List.of(new CensusSnapshot.Band("all", -64, 64)));
        for (int x = 0; x <= 20; x++) {
            s.addOpen(x, 10, 0);
            s.addOpen(x, 11, 0);
        }
        s.addOpen(21, 9, 0); s.addOpen(21, 10, 0);
        s.addOpen(22, 8, 0); s.addOpen(22, 9, 0);
        s.addOpen(23, 7, 0); s.addOpen(23, 8, 0);
        s.addOpen(100, 10, 0); s.addOpen(100, 11, 0); // sealed pocket, standable but unreachable
        s.addOre(5, 10, 1, "test:exposed_ore");
        s.addOre(50, 10, 1, "test:buried_ore");
        s.addMob(10, 10, 0);
        s.addChunk();
        return s;
    }

    @Test
    void packRoundTripsNegativeCoordinates() {
        long p = CensusSnapshot.pack(-1023, -64, 2047);
        assertEquals(-1023, CensusSnapshot.x(p));
        assertEquals(-64, CensusSnapshot.y(p));
        assertEquals(2047, CensusSnapshot.z(p));
    }

    @Test
    void countsReachableUnreachableAndRoute() {
        CensusReport r = CensusAnalyzer.analyze(fixture(), 0, 10, 0, CensusAnalyzer.Params.DEFAULT);
        assertEquals(25, r.standable());
        assertEquals(24, r.reachable());
        assertEquals(1, r.unreachable());
        assertTrue(r.startWalkable());
        assertEquals(23, r.routeSteps());
        assertEquals(23, r.routeEnd()[0]);
        assertEquals(7, r.routeEnd()[1]);
    }

    @Test
    void onlyWallAdjacentOreIsExposed() {
        CensusReport r = CensusAnalyzer.analyze(fixture(), 0, 10, 0, CensusAnalyzer.Params.DEFAULT);
        assertEquals(1, r.oreFaces());
        assertEquals(1, r.exposedOreByType().get("test:exposed_ore"));
        assertFalse(r.exposedOreByType().containsKey("test:buried_ore"));
        assertTrue(r.wallFaces() > r.oreFaces());
    }

    @Test
    void longestEmptyStretchUsesInterestRadiusAlongRoute() {
        CensusReport r = CensusAnalyzer.analyze(fixture(), 0, 10, 0, CensusAnalyzer.Params.DEFAULT);
        assertEquals(5, r.emptyMob());   // route cells x19..23 are >8 from the mob at x10
        assertEquals(11, r.emptyOre());  // route cells x13..23 are >8 from the ore at x5
        assertEquals(24, r.emptyLoot()); // no loot at all: whole route (24 cells) is empty
        assertEquals(24, r.emptyLandmark());
        assertEquals(5, r.emptyAny());   // ore and mob together leave the x19..23 tail empty
    }

    @Test
    void sameInputGivesIdenticalText() {
        List<String> a = CensusAnalyzer.analyze(fixture(), 0, 10, 0, CensusAnalyzer.Params.DEFAULT).lines();
        List<String> b = CensusAnalyzer.analyze(fixture(), 0, 10, 0, CensusAnalyzer.Params.DEFAULT).lines();
        assertEquals(a, b);
    }

    @Test
    void nonWalkableStartReportsNoRouteInsteadOfPartialData() {
        CensusReport r = CensusAnalyzer.analyze(fixture(), 0, 30, 0, CensusAnalyzer.Params.DEFAULT);
        assertFalse(r.startWalkable());
        assertEquals(0, r.reachable());
        assertTrue(r.lines().stream().anyMatch(l -> l.contains("not walkable")));
    }

    @Test
    void emptySnapshotDoesNotThrow() {
        CensusSnapshot s = new CensusSnapshot(List.of(new CensusSnapshot.Band("all", -64, 64)));
        CensusReport r = CensusAnalyzer.analyze(s, 0, 0, 0, CensusAnalyzer.Params.DEFAULT);
        assertEquals(0, r.standable());
        assertFalse(r.startWalkable());
    }
}
