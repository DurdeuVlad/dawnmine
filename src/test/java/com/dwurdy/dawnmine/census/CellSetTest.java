package com.dwurdy.dawnmine.census;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CellSetTest {

    @Test
    void addContainsAndSizeAcrossSectionsAndNegatives() {
        CellSet set = new CellSet();
        assertTrue(set.add(-1, -64, -1));
        assertFalse(set.add(-1, -64, -1));
        assertTrue(set.add(15, 0, 16));
        assertTrue(set.contains(-1, -64, -1));
        assertTrue(set.contains(15, 0, 16));
        assertFalse(set.contains(0, 0, 0));
        assertEquals(2, set.size());
    }

    @Test
    void forEachReturnsExactlyTheAddedCells() {
        CellSet set = new CellSet();
        Set<Long> expected = new HashSet<>();
        int[][] cells = {{-17, -60, 33}, {0, 0, 0}, {31, 15, -1}, {-1, -1, -1}, {1000, 100, -1000}};
        for (int[] c : cells) {
            set.add(c[0], c[1], c[2]);
            expected.add(CensusSnapshot.pack(c[0], c[1], c[2]));
        }
        Set<Long> seen = new HashSet<>();
        set.forEach(seen::add);
        assertEquals(expected, seen);
    }
}
