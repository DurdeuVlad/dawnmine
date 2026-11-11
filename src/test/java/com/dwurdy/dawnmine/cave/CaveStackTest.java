package com.dwurdy.dawnmine.cave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.dawnmine.census.CensusAnalyzer;
import com.dwurdy.dawnmine.census.CensusReport;
import com.dwurdy.dawnmine.census.CensusSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CaveStackTest {
    private static final Pattern MINUTES = Pattern.compile("waypoint (\\S+) .*: steps=\\d+ blocks=\\d+ at ([0-9.]+) min");

    private static CensusSnapshot snapshot(CaveStack stack) {
        CensusSnapshot snap = new CensusSnapshot(List.of(
                new CensusSnapshot.Band("L0", 24, 127),
                new CensusSnapshot.Band("L1", -30, 23),
                new CensusSnapshot.Band("L2", -64, -31)));
        stack.addTo(snap);
        for (int x = -21; x <= 21; x++) {
            for (int z = -21; z <= 21; z++) {
                for (int y = 42; y <= 49; y++) {
                    snap.addOpen(x, y, z);
                }
            }
        }
        return snap;
    }

    private static CensusReport walk(CaveStack stack) {
        List<CensusAnalyzer.Waypoint> wps = new ArrayList<>();
        for (CaveStack.Waypoint w : stack.waypoints()) {
            wps.add(new CensusAnalyzer.Waypoint(w.name(), w.x(), w.y(), w.z()));
        }
        return CensusAnalyzer.analyze(snapshot(stack), 0, 42, 8, CensusAnalyzer.Params.DEFAULT, wps);
    }

    private static double minutes(CensusReport r, String waypoint) {
        for (String line : r.lines()) {
            Matcher m = MINUTES.matcher(line);
            if (m.find() && m.group(1).equals(waypoint)) {
                return Double.parseDouble(m.group(2));
            }
        }
        throw new AssertionError("no walking time for " + waypoint + " in " + r.lines());
    }

    /** The whole three-layer mine, walked from the hub landing by the independent census walker. */
    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3})
    void fullStackIsWalkableAndPacedLikeTheDesign(long seed) {
        CaveStack stack = CaveStack.plan(64, seed);
        assertEquals(CaveStack.DESCENTS_PER_PAIR * (CavePlanner.LAYERS - 1), stack.descents().size());
        CensusReport r = walk(stack);
        r.lines().stream().filter(l -> l.contains("waypoint") || l.startsWith("walkable"))
                .forEach(l -> System.out.println("  seed " + seed + ": " + l));
        assertTrue(r.startWalkable());
        assertEquals(0, r.unreachable(), "standable cells the walker cannot reach");
        assertTrue(r.standable() > 2_000_000);
        for (String line : r.lines()) {
            assertFalse(line.contains("not reachable"), line);
        }
        // Layer 0: a rush from the hub to any way down is about 15 minutes (13-17 allowed).
        for (CaveStack.Descent d : stack.descents()) {
            if (d.fromLayer() == 0) {
                double t = minutes(r, d.top().name());
                assertTrue(t > 11 && t < 18, d.top().name() + " at " + t + " min");
            }
        }
        // Layer 1: another layer's worth on top of the best way out of layer 0.
        double bestOut0 = Double.MAX_VALUE;
        for (CaveStack.Descent d : stack.descents()) {
            if (d.fromLayer() == 0) {
                bestOut0 = Math.min(bestOut0, minutes(r, d.bottom().name()));
            }
        }
        for (CaveStack.Descent d : stack.descents()) {
            if (d.fromLayer() == 1) {
                double leg = minutes(r, d.top().name()) - bestOut0;
                assertTrue(leg > 9 && leg < 19, d.top().name() + " leg " + leg + " min");
            }
        }
    }

    @Test
    void descentsAreDistinctGentleAndMeetBothLayers() {
        CaveStack stack = CaveStack.plan(64, 9);
        for (int layer = 0; layer < CavePlanner.LAYERS - 1; layer++) {
            List<CaveStack.Descent> pair = new ArrayList<>();
            for (CaveStack.Descent d : stack.descents()) {
                if (d.fromLayer() == layer) {
                    pair.add(d);
                }
            }
            assertEquals(CaveStack.DESCENTS_PER_PAIR, pair.size());
            for (int i = 0; i < pair.size(); i++) {
                CaveStack.Descent d = pair.get(i);
                int n = d.length();
                // floors fall by at most 1 per step, level at both ends, tunnel starts and ends in open layers
                for (int k = 1; k < n; k++) {
                    int step = d.floors()[k - 1] - d.floors()[k];
                    assertTrue(step == 0 || step == 1, d.top().name() + " step " + step);
                    assertEquals(1, Math.abs(d.xs()[k] - d.xs()[k - 1]) + Math.abs(d.zs()[k] - d.zs()[k - 1]));
                }
                for (int k = 0; k < CaveStack.FLAT; k++) {
                    assertEquals(d.floors()[0], d.floors()[k], "level start");
                    assertEquals(d.floors()[n - 1], d.floors()[n - 1 - k], "level landing");
                }
                assertTrue(stack.sheet(layer).isOpen(d.xs()[0], d.zs()[0]), "top is open");
                assertEquals(stack.sheet(layer).floorAt(d.xs()[0], d.zs()[0]), d.floors()[0]);
                assertTrue(stack.sheet(layer + 1).isOpen(d.xs()[n - 1], d.zs()[n - 1]), "bottom is open");
                assertEquals(stack.sheet(layer + 1).floorAt(d.xs()[n - 1], d.zs()[n - 1]), d.floors()[n - 1]);
                for (int j = i + 1; j < pair.size(); j++) {
                    CaveStack.Waypoint a = d.top();
                    CaveStack.Waypoint b = pair.get(j).top();
                    double apart = Math.hypot(a.x() - b.x(), a.z() - b.z());
                    assertTrue(apart >= 100, "ways down are apart: " + apart);
                    assertTrue(apart <= 450, "ways down sit together: " + apart);
                    assertFalse(d.name().equals(pair.get(j).name()), "distinct characters");
                }
            }
        }
    }

    @Test
    void sameSeedSameStack() {
        CaveStack a = CaveStack.plan(64, 7);
        CaveStack b = CaveStack.plan(64, 7);
        assertEquals(a.descents().size(), b.descents().size());
        for (int i = 0; i < a.descents().size(); i++) {
            assertTrue(java.util.Arrays.equals(a.descents().get(i).xs(), b.descents().get(i).xs()));
            assertTrue(java.util.Arrays.equals(a.descents().get(i).floors(), b.descents().get(i).floors()));
        }
        for (int layer = 0; layer < a.layers(); layer++) {
            assertEquals(a.sheet(layer).openColumns(), b.sheet(layer).openColumns());
        }
        assertFalse(a.sheet(0).openColumns() == CaveStack.plan(64, 8).sheet(0).openColumns());
    }
    /** Planning must never throw for ordinary daily seeds: it is on the path of every chunk and reset. */
    @Test
    void manySeedsPlanWithoutFailing() {
        for (long seed = 20; seed < 26; seed++) {
            CaveStack stack = CaveStack.plan(64, seed * 7919L);
            assertEquals(6, stack.descents().size(), "seed " + seed);
        }
    }

    /** No tunnel column may lie in the ridge of the layer it leaves, so a tunnel can never cut a ridge. */
    @Test
    void tunnelsNeverCrossTheRidgeOfTheLayerAbove() {
        long seed = 4;
        CaveStack stack = CaveStack.plan(64, seed);
        int[][] entries = {{36, 0}};
        for (int layer = 0; layer < CavePlanner.LAYERS - 1; layer++) {
            CavePlanner.Fields fields = CavePlanner.fields(CavePlanner.Spec.layer(layer, 64), seed);
            java.util.BitSet ridge = CaveStack.ridge(layer, fields, entries);
            assertTrue(ridge.cardinality() > 5_000, "the ridge exists on layer " + layer);
            List<int[]> landings = new ArrayList<>();
            for (CaveStack.Descent d : stack.descents()) {
                if (d.fromLayer() != layer) {
                    continue;
                }
                for (int k = 0; k < d.length(); k++) {
                    assertFalse(ridge.get(fields.index(d.xs()[k], d.zs()[k])),
                            d.top().name() + " crosses the ridge at " + d.xs()[k] + "," + d.zs()[k]);
                }
                landings.add(new int[] {d.xs()[d.length() - 1], d.zs()[d.length() - 1]});
            }
            entries = landings.toArray(new int[0][]);
        }
    }
}
