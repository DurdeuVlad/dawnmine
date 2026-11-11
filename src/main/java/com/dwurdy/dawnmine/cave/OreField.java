package com.dwurdy.dawnmine.cave;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ore veins for a planned cave stack, as a pure function of (stack, run seed, ore tables).
 * Chunk generation and the reset baseline both ask this class, so ore needs no per-block record:
 * a reset simply regenerates the same field from the new run's seed.
 *
 * <p>Each layer is cut into {@link #CELL}-wide grid cells. A cell owns a few veins whose anchor
 * is a rock block on the wall of an open column, so every ordinary vein is partly visible from
 * the walking floor; a share is buried deeper ("hidden"). Veins grow by a bounded random walk
 * through solid rock only. A few motherlodes per layer are dense clusters of one rich ore.
 * A block's final ore is the last vein in a fixed global order that covers it, so the answer
 * never depends on which chunk is being generated.
 */
public final class OreField {
    /** One catalogued ore as the field sees it. {@code grade} (1..3) scales vein size. */
    public record Ore(String id, int weight, int grade) { }

    public static final int CELL = 12;
    /** Vein growth stays within this Chebyshev distance of its anchor. */
    public static final int REACH = 6;
    public static final int MOTHERLODES_PER_LAYER = 3;
    public static final int MOTHERLODE_VEINS = 45;
    public static final int MOTHERLODE_RADIUS = 12;
    public static final int MOTHERLODE_REACH = 14;
    /** Expected veins per cell on layers 0, 1, 2 (richer deeper). */
    public static final double[] VEINS_PER_CELL = {7.5, 11.0, 15.0};
    /** A buried anchor sits up to 3 blocks outside its cell, so a vein reaches REACH + 3 past the cell edge. */
    private static final int CELL_MARGIN = REACH + 3;
    private static final int LODE_MARGIN = MOTHERLODE_RADIUS + MOTHERLODE_REACH + 3;
    private static final int BURIED_PERCENT = 20;
    private static final int COARSE = 512;
    private static final int[][] DIRS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** Ore blocks inside a box: column key (see {@link CaveStack#key}) to {y, oreIndex, ...}. */
    public record Blocks(Map<Long, int[]> byColumn) { }

    /** A grown vein: ore index plus x,y,z triples. Independent of any chunk. */
    private record Vein(int ore, int[] xyz) { }

    private record Motherlode(int layer, int x, int z, int ore) { }

    private final CaveStack stack;
    private final Rock rock;
    private final long seed;
    private final List<List<Ore>> tables;
    private final List<Ore> ids = new ArrayList<>();
    private final List<Motherlode> motherlodes = new ArrayList<>();
    private final LinkedHashMap<Long, List<Vein>> cellCache = new LinkedHashMap<>(256, 0.75f, true);
    private final java.util.concurrent.ConcurrentHashMap<Integer, List<Vein>> lodeCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final LinkedHashMap<Long, Blocks> chunkCache = new LinkedHashMap<>(64, 0.75f, true);

    public OreField(CaveStack stack, long seed, List<List<Ore>> tables) {
        this.stack = stack;
        this.rock = new Rock(stack);
        this.seed = seed;
        this.tables = tables;
        for (List<Ore> table : tables) {
            for (Ore ore : table) {
                if (!ids.contains(ore)) {
                    ids.add(ore);
                }
            }
        }
        for (int layer = 0; layer < Math.min(stack.layers(), tables.size()); layer++) {
            if (!tables.get(layer).isEmpty()) {
                findMotherlodes(layer);
            }
        }
    }

    public int oreCount() {
        return ids.size();
    }

    /** The ore for an index stored in {@link Blocks}. */
    public Ore ore(int index) {
        return ids.get(index);
    }

    public int motherlodes(int layer) {
        return (int) motherlodes.stream().filter(m -> m.layer == layer).count();
    }

    /** Centres of the motherlodes of a layer, as {x, z}. */
    public List<int[]> motherlodeCentres(int layer) {
        List<int[]> out = new ArrayList<>();
        for (Motherlode m : motherlodes) {
            if (m.layer == layer) {
                out.add(new int[] {m.x, m.z});
            }
        }
        return out;
    }

    /** Ore of one chunk (cached: the reset baseline asks column by column). */
    public Blocks forChunk(int chunkX, int chunkZ) {
        long key = ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
        synchronized (chunkCache) {
            Blocks hit = chunkCache.get(key);
            if (hit != null) {
                return hit;
            }
        }
        Blocks made = forBox(chunkX * 16, chunkZ * 16, chunkX * 16 + 15, chunkZ * 16 + 15);
        synchronized (chunkCache) {
            chunkCache.put(key, made);
            while (chunkCache.size() > 96) {
                chunkCache.remove(chunkCache.keySet().iterator().next());
            }
        }
        return made;
    }

    /** Ore inside an x/z box (inclusive). Veins overlapping the box are generated in full order. */
    public Blocks forBox(int minX, int minZ, int maxX, int maxZ) {
        Map<Long, int[]> out = new java.util.HashMap<>();
        for (int layer = 0; layer < Math.min(stack.layers(), tables.size()); layer++) {
            if (tables.get(layer).isEmpty()) {
                continue;
            }
            int gx0 = Math.floorDiv(minX - CELL_MARGIN, CELL);
            int gx1 = Math.floorDiv(maxX + CELL_MARGIN, CELL);
            int gz0 = Math.floorDiv(minZ - CELL_MARGIN, CELL);
            int gz1 = Math.floorDiv(maxZ + CELL_MARGIN, CELL);
            for (int gz = gz0; gz <= gz1; gz++) {
                for (int gx = gx0; gx <= gx1; gx++) {
                    for (Vein v : cell(layer, gx, gz)) {
                        clip(v, minX, minZ, maxX, maxZ, out);
                    }
                }
            }
            for (int i = 0; i < motherlodes.size(); i++) {
                Motherlode m = motherlodes.get(i);
                if (m.layer == layer && m.x >= minX - LODE_MARGIN && m.x <= maxX + LODE_MARGIN
                        && m.z >= minZ - LODE_MARGIN && m.z <= maxZ + LODE_MARGIN) {
                    for (Vein v : lode(m, i)) {
                        clip(v, minX, minZ, maxX, maxZ, out);
                    }
                }
            }
        }
        return new Blocks(out);
    }

    // ---------- veins ----------

    private List<Vein> cell(int layer, int gx, int gz) {
        long key = (((long) layer) << 56) ^ (((long) gx & 0xFFFFFFFL) << 28) ^ ((long) gz & 0xFFFFFFFL);
        synchronized (cellCache) {
            List<Vein> hit = cellCache.get(key);
            if (hit != null) {
                return hit;
            }
        }
        List<Vein> made = growCell(layer, gx, gz);
        synchronized (cellCache) {
            cellCache.put(key, made);
            while (cellCache.size() > 4096) {
                cellCache.remove(cellCache.keySet().iterator().next());
            }
        }
        return made;
    }

    private List<Vein> growCell(int layer, int gx, int gz) {
        Rng rng = new Rng(seed ^ (layer * 0x9E3779B97F4A7C15L) ^ (gx * 0xC2B2AE3D27D4EB4FL)
                ^ (gz * 0x165667B19E3779F9L));
        double expected = VEINS_PER_CELL[Math.min(layer, VEINS_PER_CELL.length - 1)];
        int count = (int) expected + (rng.nextDouble() < expected - (int) expected ? 1 : 0);
        List<Vein> veins = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            int[] anchor = wallAnchor(layer, rng, gx * CELL, gz * CELL, CELL, rng.nextInt(100) < BURIED_PERCENT);
            if (anchor == null) {
                continue;
            }
            int oreIndex = pick(layer, rng);
            Ore ore = ids.get(oreIndex);
            int size = 5 + ore.grade() * (1 + rng.nextInt(4));
            veins.add(grow(rng, anchor, oreIndex, size, REACH));
        }
        return veins;
    }

    private List<Vein> lode(Motherlode m, int index) {
        return lodeCache.computeIfAbsent(index, i -> {
            Rng rng = new Rng(seed ^ 0x5DEECE66DL ^ (index * 0x9E3779B97F4A7C15L));
            List<Vein> veins = new ArrayList<>();
            for (int k = 0; k < MOTHERLODE_VEINS; k++) {
                int[] anchor = wallAnchor(m.layer, rng, m.x - MOTHERLODE_RADIUS, m.z - MOTHERLODE_RADIUS,
                        MOTHERLODE_RADIUS * 2, false);
                if (anchor != null) {
                    veins.add(grow(rng, anchor, m.ore, 20 + rng.nextInt(21), MOTHERLODE_REACH));
                }
            }
            return veins;
        });
    }

    private static void clip(Vein v, int minX, int minZ, int maxX, int maxZ, Map<Long, int[]> out) {
        int[] c = v.xyz();
        for (int i = 0; i < c.length; i += 3) {
            if (c[i] >= minX && c[i] <= maxX && c[i + 2] >= minZ && c[i + 2] <= maxZ) {
                put(out, c[i], c[i + 1], c[i + 2], v.ore());
            }
        }
    }

    /** A rock block touching an open column of the layer, inside the square; null after 40 tries. */
    private int[] wallAnchor(int layer, Rng rng, int x0, int z0, int span, boolean buried) {
        CaveSheet sheet = stack.sheet(layer);
        for (int attempt = 0; attempt < 40; attempt++) {
            int x = x0 + rng.nextInt(span);
            int z = z0 + rng.nextInt(span);
            if (!sheet.isOpen(x, z)) {
                continue;
            }
            int[] d = DIRS[rng.nextInt(4)];
            int y = sheet.floorAt(x, z) + 1 + rng.nextInt(3);
            int step = buried ? 3 : 1;
            int ax = x + d[0] * step;
            int az = z + d[2] * step;
            if (solid(ax, y, az) && (!buried || solid(x + d[0] * 2, y, z + d[2] * 2))) {
                return new int[] {ax, y, az};
            }
        }
        return null;
    }

    private Vein grow(Rng rng, int[] anchor, int oreIndex, int size, int reach) {
        int[][] cells = new int[size][];
        cells[0] = anchor;
        int n = 1;
        for (int attempts = 0; n < size && attempts < size * 4; attempts++) {
            int[] from = cells[rng.nextInt(n)];
            int[] d = DIRS[rng.nextInt(6)];
            int x = from[0] + d[0];
            int y = from[1] + d[1];
            int z = from[2] + d[2];
            if (Math.abs(x - anchor[0]) > reach || Math.abs(y - anchor[1]) > reach
                    || Math.abs(z - anchor[2]) > reach || !solid(x, y, z)) {
                continue;
            }
            // first half of the attempts hug the wall, so veins show along the cave rather than sink into rock
            if (attempts < size * 2 && !touchesOpen(x, y, z)) {
                continue;
            }
            boolean seen = false;
            for (int i = 0; i < n && !seen; i++) {
                seen = cells[i][0] == x && cells[i][1] == y && cells[i][2] == z;
            }
            if (!seen) {
                cells[n++] = new int[] {x, y, z};
            }
        }
        int[] xyz = new int[n * 3];
        for (int i = 0; i < n; i++) {
            xyz[i * 3] = cells[i][0];
            xyz[i * 3 + 1] = cells[i][1];
            xyz[i * 3 + 2] = cells[i][2];
        }
        return new Vein(oreIndex, xyz);
    }

    /** Later veins replace earlier ones on the same block; a column holds a few ore blocks. */
    private static void put(Map<Long, int[]> out, int x, int y, int z, int oreIndex) {
        long key = CaveStack.key(x, z);
        int[] col = out.get(key);
        if (col == null) {
            out.put(key, new int[] {y, oreIndex});
            return;
        }
        for (int i = 0; i < col.length; i += 2) {
            if (col[i] == y) {
                col[i + 1] = oreIndex;
                return;
            }
        }
        int[] bigger = java.util.Arrays.copyOf(col, col.length + 2);
        bigger[col.length] = y;
        bigger[col.length + 1] = oreIndex;
        out.put(key, bigger);
    }

    private int pick(int layer, Rng rng) {
        List<Ore> table = tables.get(layer);
        int total = 0;
        for (Ore ore : table) {
            total += ore.weight();
        }
        int roll = rng.nextInt(total);
        for (Ore ore : table) {
            roll -= ore.weight();
            if (roll < 0) {
                return ids.indexOf(ore);
            }
        }
        throw new IllegalStateException("weighted pick escaped its table");
    }

    // ---------- motherlodes ----------

    /** The three best coarse cells by hash that hold an open wall column away from the hub. */
    private void findMotherlodes(int layer) {
        CaveSheet sheet = stack.sheet(layer);
        List<long[]> ranked = new ArrayList<>();
        for (int cz = Math.floorDiv(sheet.minZ(), COARSE); cz <= Math.floorDiv(sheet.maxZ(), COARSE); cz++) {
            for (int cx = Math.floorDiv(sheet.minX(), COARSE); cx <= Math.floorDiv(sheet.maxX(), COARSE); cx++) {
                Rng rng = new Rng(seed ^ 0xA0761D6478BD642FL ^ (layer * 31L + cx) * 0xE7037ED1A0B428DBL ^ cz);
                ranked.add(new long[] {rng.next(), cx, cz});
            }
        }
        ranked.sort((a, b) -> Long.compare(a[0], b[0]));
        for (long[] cell : ranked) {
            if (motherlodes.stream().filter(m -> m.layer == layer).count() >= MOTHERLODES_PER_LAYER) {
                break;
            }
            Rng rng = new Rng(cell[0]);
            for (int attempt = 0; attempt < 600; attempt++) {
                int x = (int) cell[1] * COARSE + rng.nextInt(COARSE);
                int z = (int) cell[2] * COARSE + rng.nextInt(COARSE);
                if (sheet.isOpen(x, z) && Math.abs(x) + Math.abs(z) > 150
                        && !protectedColumn(x, z) && openAround(sheet, x, z)) {
                    List<Ore> table = tables.get(layer);
                    int rich = 0;
                    for (int i = 1; i < table.size(); i++) {
                        if (table.get(i).weight() < table.get(rich).weight()) {
                            rich = i;
                        }
                    }
                    motherlodes.add(new Motherlode(layer, x, z, ids.indexOf(table.get(rich))));
                    break;
                }
            }
        }
    }

    /** Wide open space around the point, so the lode sits in a cavern a player can reach. */
    private static boolean openAround(CaveSheet sheet, int x, int z) {
        int open = 0;
        for (int dz = -6; dz <= 6; dz += 2) {
            for (int dx = -6; dx <= 6; dx += 2) {
                if (sheet.isOpen(x + dx, z + dz)) {
                    open++;
                }
            }
        }
        return open >= 25;
    }

    // ---------- terrain tests (shared with landmarks) ----------

    /** Hub box and its forecourt: never any ore (protected volumes). */
    public static boolean protectedColumn(int x, int z) {
        return Rock.protectedColumn(x, z);
    }

    public boolean solid(int x, int y, int z) {
        return rock.solid(x, y, z);
    }

    private boolean touchesOpen(int x, int y, int z) {
        return rock.touchesOpen(x, y, z);
    }

    public boolean isOpenCell(int x, int y, int z) {
        return rock.isOpenCell(x, y, z);
    }
}
