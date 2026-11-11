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
        s.addOpen(21, 9, 0); s.addOpen(21, 10, 0); s.addOpen(21, 11, 0);
        s.addOpen(22, 8, 0); s.addOpen(22, 9, 0); s.addOpen(22, 10, 0);
        s.addOpen(23, 7, 0); s.addOpen(23, 8, 0); s.addOpen(23, 9, 0);
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
    private static CensusSnapshot flat() {
        return new CensusSnapshot(List.of(new CensusSnapshot.Band("all", -64, 64)));
    }

    private static void corridor(CensusSnapshot s, int x0, int x1, int y, int z) {
        for (int x = x0; x <= x1; x++) {
            s.addOpen(x, y, z);
            s.addOpen(x, y + 1, z);
        }
    }

    @Test
    void lowCeilingOverADropBlocksTheStepDown() {
        CensusSnapshot s = flat();
        corridor(s, 0, 3, 10, 0);
        s.addOpen(4, 9, 0); s.addOpen(4, 10, 0); // lower cell, but (4,11) is solid rock
        CensusReport r = CensusAnalyzer.analyze(s, 0, 10, 0, CensusAnalyzer.Params.DEFAULT);
        assertEquals(5, r.standable());
        assertEquals(4, r.reachable());
    }

    @Test
    void stepDownWithClearHeadroomIsWalkable() {
        CensusSnapshot s = flat();
        corridor(s, 0, 3, 10, 0);
        s.addOpen(4, 11, 0);                      // clears the drop's head space
        s.addOpen(4, 9, 0); s.addOpen(4, 10, 0);
        CensusReport r = CensusAnalyzer.analyze(s, 0, 10, 0, CensusAnalyzer.Params.DEFAULT);
        assertEquals(5, r.reachable());
    }

    @Test
    void stepUpNeedsHeadroomAboveTheCurrentCell() {
        CensusSnapshot blocked = flat();
        corridor(blocked, 0, 0, 10, 0);          // (0,10),(0,11) open, (0,12) solid
        blocked.addOpen(1, 11, 0); blocked.addOpen(1, 12, 0);
        CensusReport rb = CensusAnalyzer.analyze(blocked, 0, 10, 0, CensusAnalyzer.Params.DEFAULT);
        assertEquals(1, rb.reachable());

        CensusSnapshot open = flat();
        corridor(open, 0, 0, 10, 0);
        open.addOpen(0, 12, 0);
        open.addOpen(1, 11, 0); open.addOpen(1, 12, 0);
        CensusReport ro = CensusAnalyzer.analyze(open, 0, 10, 0, CensusAnalyzer.Params.DEFAULT);
        assertEquals(2, ro.reachable());
    }

    @Test
    void diagonalStepNeedsBothCornerColumnsClear() {
        CensusSnapshot oneCorner = flat();
        corridor(oneCorner, 0, 0, 10, 0);
        corridor(oneCorner, 1, 1, 10, 1);
        oneCorner.addOpen(1, 10, 0); oneCorner.addOpen(1, 11, 0);   // only one corner column open
        // (1,10,0) is itself standable and reached orthogonally; (1,10,1) then follows orthogonally.
        // Remove the orthogonal path by using the diagonal-only layout below instead.
        CensusSnapshot diagOnly = flat();
        corridor(diagOnly, 0, 0, 10, 0);
        corridor(diagOnly, 1, 1, 10, 1);
        CensusReport blocked = CensusAnalyzer.analyze(diagOnly, 0, 10, 0, CensusAnalyzer.Params.DEFAULT);
        assertEquals(2, blocked.standable());
        assertEquals(1, blocked.reachable());   // rock at both corners: no corner cutting

        CensusReport viaCorner = CensusAnalyzer.analyze(oneCorner, 0, 10, 0, CensusAnalyzer.Params.DEFAULT);
        assertEquals(3, viaCorner.reachable()); // reachable orthogonally through the open corner
    }

    @Test
    void headlineTotalsCoverOnlyTheBands() {
        CensusSnapshot s = new CensusSnapshot(List.of(new CensusSnapshot.Band("low", 0, 20)));
        corridor(s, 0, 3, 10, 0);   // inside the band
        corridor(s, 0, 3, 40, 0);   // standable but above every band: not mine space
        s.addOre(1, 40, 1, "test:sky_ore");
        CensusReport r = CensusAnalyzer.analyze(s, 0, 10, 0, CensusAnalyzer.Params.DEFAULT);
        assertEquals(4, r.standable());
        assertEquals(0, r.unreachable());
    }
    @Test
    void fluidIsNotAFloorAndNegativeCoordinatesWalkFine() {
        CensusSnapshot s = flat();
        corridor(s, -5, -1, 10, -3);          // walkable at negative x and z
        s.addOpen(0, 10, -3); s.addOpen(0, 11, -3);
        s.addFluid(0, 9, -3);                 // water below this cell: cannot stand on it
        CensusReport r = CensusAnalyzer.analyze(s, -5, 10, -3, CensusAnalyzer.Params.DEFAULT);
        assertEquals(5, r.standable());
        assertEquals(5, r.reachable());
    }

    @Test
    void routeOfJustTheStartCellHasZeroStepsAndFullEmptyStretch() {
        CensusSnapshot s = flat();
        corridor(s, 0, 0, 10, 0);
        CensusReport r = CensusAnalyzer.analyze(s, 0, 10, 0, CensusAnalyzer.Params.DEFAULT);
        assertEquals(0, r.routeSteps());
        assertEquals(1, r.emptyAny());
    }

    @Test
    void bandsSplitStandableAndRouteStepsByY() {
        CensusSnapshot s = new CensusSnapshot(List.of(
                new CensusSnapshot.Band("a", 8, 10), new CensusSnapshot.Band("b", 5, 7)));
        corridor(s, 0, 3, 10, 0);
        s.addOpen(4, 9, 0); s.addOpen(4, 10, 0); s.addOpen(4, 11, 0);
        s.addOpen(5, 8, 0); s.addOpen(5, 9, 0); s.addOpen(5, 10, 0);
        s.addOpen(6, 7, 0); s.addOpen(6, 8, 0); s.addOpen(6, 9, 0);
        CensusReport r = CensusAnalyzer.analyze(s, 0, 10, 0, CensusAnalyzer.Params.DEFAULT);
        assertEquals(7, r.standable());
        assertEquals(0, r.unreachable());
        assertEquals(6, r.routeStepsPerBand()[0] + r.routeStepsPerBand()[1] - 1 + 0 * 1);
        assertEquals(1, r.routeStepsPerBand()[1]);
    }
}
