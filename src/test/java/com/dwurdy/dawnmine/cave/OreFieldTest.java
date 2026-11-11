package com.dwurdy.dawnmine.cave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OreFieldTest {
    private static List<List<OreField.Ore>> tables() {
        List<List<OreField.Ore>> t = new ArrayList<>();
        for (int layer = 0; layer < 3; layer++) {
            List<OreField.Ore> table = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                table.add(new OreField.Ore("test:l" + layer + "_ore" + i, i == 9 ? 1 : 5, 1 + i % 3));
            }
            t.add(table);
        }
        return t;
    }

    private static final Map<Long, CaveStack> STACKS = new HashMap<>();

    private static synchronized CaveStack stack(long seed) {
        return STACKS.computeIfAbsent(seed, s -> CaveStack.plan(64, s));
    }

    private static boolean openAt(CaveStack stack, int x, int y, int z) {
        for (int layer = 0; layer < stack.layers(); layer++) {
            CaveSheet s = stack.sheet(layer);
            if (s.isOpen(x, z) && y > s.floorAt(x, z) && y <= s.floorAt(x, z) + s.heightAt(x, z)) {
                return true;
            }
        }
        int[] spans = stack.tubeSpans(x, z);
        if (spans != null) {
            for (int i = 0; i < spans.length; i += 2) {
                if (y >= spans[i] && y <= spans[i + 1]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long count(OreField.Blocks b) {
        return b.byColumn().values().stream().mapToLong(c -> c.length / 2).sum();
    }

    @Test
    void sameSeedSameOreAndChunkEdgesAgree() {
        CaveStack stack = stack(1);
        OreField a = new OreField(stack, 1, tables());
        OreField b = new OreField(stack, 1, tables());
        OreField.Blocks left = a.forBox(300, 300, 315, 315);
        OreField.Blocks right = a.forBox(316, 300, 331, 315);
        OreField.Blocks both = b.forBox(300, 300, 331, 315);
        assertEquals(count(both), count(left) + count(right));
        for (var e : both.byColumn().entrySet()) {
            int x = (int) (e.getKey() >> 32);
            OreField.Blocks part = x < 316 ? left : right;
            int[] got = part.byColumn().get(e.getKey());
            assertTrue(got != null && java.util.Arrays.equals(got, e.getValue()), "column differs at x=" + x);
        }
        assertTrue(count(both) > 0, "ore exists in this window");
    }

    /** Any chunk's ore equals the same area cut from a much larger box: no vein is clipped at a chunk border. */
    @Test
    void chunkOreMatchesALargeBoxEverywhere() {
        CaveStack stack = stack(1);
        OreField field = new OreField(stack, 1, tables());
        for (int[] c : new int[][] {{20, 20}, {-33, 7}, {40, -25}, {3, 51}, {-50, -50}}) {
            OreField.Blocks chunk = field.forChunk(c[0], c[1]);
            OreField.Blocks wide = field.forBox(c[0] * 16 - 48, c[1] * 16 - 48, c[0] * 16 + 63, c[1] * 16 + 63);
            long inside = 0;
            for (var e : wide.byColumn().entrySet()) {
                int x = (int) (e.getKey() >> 32);
                int z = (int) (long) e.getKey();
                if (x >= c[0] * 16 && x < c[0] * 16 + 16 && z >= c[1] * 16 && z < c[1] * 16 + 16) {
                    inside += e.getValue().length / 2;
                    assertTrue(java.util.Arrays.equals(chunk.byColumn().get(e.getKey()), e.getValue()),
                            "chunk " + c[0] + "," + c[1] + " differs from the wide box at " + x + "," + z);
                }
            }
            assertEquals(count(chunk), inside, "ore count of chunk " + c[0] + "," + c[1]);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2})
    void oreOnlyReplacesRockAndIsPartlyExposed(long seed) {
        CaveStack stack = stack(seed);
        OreField field = new OreField(stack, seed, tables());
        int[][] windows = {{200, 200}, {-500, 300}, {600, -400}};
        double[] target = {0.06, 0.10, 0.14};
        double[] shares = new double[3];
        for (int layer = 0; layer < 3; layer++) {
            long wallFaces = 0;
            long oreFaces = 0;
            for (int[] w : windows) {
                OreField.Blocks ore = field.forBox(w[0], w[1], w[0] + 159, w[1] + 159);
                Set<Long> placed = new HashSet<>();
                for (var e : ore.byColumn().entrySet()) {
                    int x = (int) (e.getKey() >> 32);
                    int z = (int) (long) e.getKey();
                    int[] c = e.getValue();
                    for (int i = 0; i < c.length; i += 2) {
                        assertFalse(openAt(stack, x, c[i], z), "ore inside open space at " + x + "," + c[i] + "," + z);
                        assertFalse(OreField.protectedColumn(x, z), "ore in protected volume");
                        placed.add(com.dwurdy.dawnmine.census.CensusSnapshot.pack(x, c[i], z));
                    }
                }
                CaveSheet s = stack.sheet(layer);
                int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int x = w[0]; x < w[0] + 160; x++) {
                    for (int z = w[1]; z < w[1] + 160; z++) {
                        if (!s.isOpen(x, z)) {
                            continue;
                        }
                        for (int y = s.floorAt(x, z) + 1; y <= s.floorAt(x, z) + s.heightAt(x, z); y++) {
                            for (int[] d : dirs) {
                                if (!openAt(stack, x + d[0], y, z + d[1])) {
                                    wallFaces++;
                                    if (placed.contains(com.dwurdy.dawnmine.census.CensusSnapshot.pack(x + d[0], y, z + d[1]))) {
                                        oreFaces++;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            double share = (double) oreFaces / wallFaces;
            System.out.println("  seed " + seed + " layer " + layer + ": exposed ore share " + share
                    + " of " + wallFaces + " wall faces");
            shares[layer] = share;
        }
        for (int layer = 0; layer < 3; layer++) {
            assertTrue(shares[layer] >= target[layer], "layer " + layer + " exposed ore share " + shares[layer]);
        }
    }

    @Test
    void everyOreAppearsOnEveryLayerAndMotherlodesExist() {
        CaveStack stack = stack(3);
        OreField field = new OreField(stack, 3, tables());
        for (int layer = 0; layer < 3; layer++) {
            assertEquals(OreField.MOTHERLODES_PER_LAYER, field.motherlodes(layer), "motherlodes on layer " + layer);
        }
        Set<Integer> seen = new HashSet<>();
        for (int x = -1000; x < 1000; x += 160) {
            for (int z = -1000; z < 1000; z += 160) {
                for (var c : field.forBox(x, z, x + 159, z + 159).byColumn().values()) {
                    for (int i = 0; i < c.length; i += 2) {
                        seen.add(c[i + 1]);
                    }
                }
            }
        }
        assertEquals(field.oreCount(), seen.size(), "every catalogued ore is placed somewhere");
        for (int layer = 0; layer < 3; layer++) {
            int[] at = field.motherlodeCentres(layer).get(0);
            long rich = 0;
            for (var c : field.forBox(at[0] - 30, at[1] - 30, at[0] + 30, at[1] + 30).byColumn().values()) {
                for (int i = 0; i < c.length; i += 2) {
                    if (field.ore(c[i + 1]).id().equals("test:l" + layer + "_ore9")) {
                        rich++;
                    }
                }
            }
            assertTrue(rich > 150, "motherlode on layer " + layer + " holds " + rich + " ore blocks");
        }
    }
}
