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
                new CensusSnapshot.Band("L2", -64, -31),
                new CensusSnapshot.Band("L3", -92, -65)));
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
        long mainDescents = stack.descents().stream().filter(d -> !"dropper".equals(d.name())).count();
        assertEquals(CaveStack.DESCENTS_PER_PAIR * (CavePlanner.LAYERS - 1), mainDescents);
        CensusReport r = walk(stack);
        r.lines().stream().filter(l -> l.contains("waypoint") || l.startsWith("walkable"))
                .forEach(l -> System.out.println("  seed " + seed + ": " + l));
        assertTrue(r.startWalkable());
        assertEquals(0, r.unreachable(), "standable cells the walker cannot reach");
        assertTrue(r.standable() > 2_000_000);
        for (String line : r.lines()) {
            assertFalse(line.contains("not reachable"), line);
        }
        // Layer 0: a rush from the hub to a way down, placed as far out as the layer safely
        // allows. The 8 compass directions wind through the cave differently, so actual walking
        // time varies noticeably by direction - this bound is wide enough to cover that spread,
        // not just the single direction tested before staircases were spread radially around
        // the whole hub.
        for (CaveStack.Descent d : stack.descents()) {
            if (d.fromLayer() == 0) {
                double t = minutes(r, d.top().name());
                assertTrue(t > 2.5 && t < 12, d.top().name() + " at " + t + " min");
            }
        }
        // Layer 1: with 8 entrances and 8 exits now, this is a many-to-many comparison, not a
        // single matched route - the single best layer-0 exit can legitimately sit right next to
        // one specific layer-1 stair (a short leg) while being far from another (a long one), so
        // this only sanity-checks the leg is positive and not absurd, not a tight pacing target.
        double bestOut0 = Double.MAX_VALUE;
        for (CaveStack.Descent d : stack.descents()) {
            if (d.fromLayer() == 0) {
                bestOut0 = Math.min(bestOut0, minutes(r, d.bottom().name()));
            }
        }
        for (CaveStack.Descent d : stack.descents()) {
            if (d.fromLayer() == 1) {
                double leg = minutes(r, d.top().name()) - bestOut0;
                assertTrue(leg > 1 && leg < 15, d.top().name() + " leg " + leg + " min");
            }
        }
    }

    /** Regression test for a real live failure: on this exact seed, the chaos-to-nether descent's
     * landing point was in a small region that {@code CavePlanner.finish}'s region-joining picked
     * as "not main" (main was chosen purely by size, with nothing telling it to keep the layer's
     * own entry point), so it got silently closed back to solid rock - stranding the only way into
     * layer 3 with no error anywhere. Confirmed live via {@code /dawnmine census}: its route report
     * showed "L2-steep-bottom: not reachable". Fixed by having {@code finish} treat a non-hub
     * layer's first pin (always its incoming landing) as the region to keep, the same way the hub
     * layer already keeps its forecourt. */
    @Test
    void chaosToNetherLandingIsReachableOnThePreviouslyFailingLiveSeed() {
        CaveStack stack = CaveStack.plan(64, -3212210442399364488L);
        CensusReport r = walk(stack);
        assertTrue(r.startWalkable());
        for (String line : r.lines()) {
            assertFalse(line.contains("not reachable"), line);
        }
    }

    @Test
    void descentsAreDistinctGentleAndMeetBothLayers() {
        CaveStack stack = CaveStack.plan(64, 9);
        for (int layer = 0; layer < CavePlanner.LAYERS - 1; layer++) {
            List<CaveStack.Descent> pair = new ArrayList<>();
            for (CaveStack.Descent d : stack.descents()) {
                if (d.fromLayer() == layer && !"dropper".equals(d.name())) {
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
                    // Fixed radial placement spreads the 8 tops around the whole hub by design -
                    // no upper "sit together" bound applies, only "distinct, not duplicates".
                    assertTrue(apart >= 100, "ways down are apart: " + apart);
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
            long mainDescents = stack.descents().stream().filter(d -> !"dropper".equals(d.name())).count();
            assertEquals(CaveStack.DESCENTS_PER_PAIR * (CavePlanner.LAYERS - 1), mainDescents, "seed " + seed);
        }
    }

    // The ridge mechanism (one long rock wall forcing a detour) was removed when staircases
    // became fixed, radially-placed spots: at a guaranteed 1500+ block straight-line distance
    // from the hub in every direction, "a real journey rather than a short hop" is already
    // guaranteed by the placement itself, and a ridge built from entries spread symmetrically in
    // all 8 compass directions has no single "far side" left to sit on. See git history for the
    // removed CaveStack.ridge and this test's prior form.
}
