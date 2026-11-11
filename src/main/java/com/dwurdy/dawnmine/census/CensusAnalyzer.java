package com.dwurdy.dawnmine.census;

import static com.dwurdy.dawnmine.census.CensusSnapshot.pack;
import static com.dwurdy.dawnmine.census.CensusSnapshot.x;
import static com.dwurdy.dawnmine.census.CensusSnapshot.y;
import static com.dwurdy.dawnmine.census.CensusSnapshot.z;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Pure census analysis over a {@link CensusSnapshot}. Generator-independent:
 * it judges the blocks that exist, not the plan that produced them.
 *
 * <p>Walking model: a cell is standable when it and the cell above are open
 * and the cell below is neither open nor fluid. Movement is 8-directional
 * with at most one block of rise or fall per step, a clear head cell when
 * stepping up, and clear corner columns on diagonals. Drops of more than one
 * block are not traversed, so a ledge reachable only by a longer fall counts
 * as unreachable (conservative; a route model with safe falls comes later).
 */
public final class CensusAnalyzer {
    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int[][] FACES = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** Tunable inputs. */
    public record Params(double walkBlocksPerSecond, int interestRadius) {
        public static final Params DEFAULT = new Params(4.3, 8);
    }

    private CensusAnalyzer() { }

    public static CensusReport analyze(CensusSnapshot s, int startX, int startY, int startZ,
            Params params) {
        int bandCount = s.bands().size();
        long start = pack(startX, startY, startZ);

        // 1. Walkable reachability with parents for route reconstruction.
        Long2LongOpenHashMap parent = new Long2LongOpenHashMap();
        Long2IntOpenHashMap dist = new Long2IntOpenHashMap();
        boolean startWalkable = standable(s, start);
        if (startWalkable) {
            walk(s, start, parent, dist);
        }
        int[] standablePerBand = new int[bandCount];
        int[] unreachablePerBand = new int[bandCount];
        s.forEachOpen(p -> {
            if (standable(s, p)) {
                int b = s.bandIndex(y(p));
                if (b >= 0) {
                    standablePerBand[b]++;
                    if (!dist.containsKey(p)) {
                        unreachablePerBand[b]++;
                    }
                }
            }
        });
        // Headline totals cover only cells inside the mine's own bands: the
        // dimension has open air above and around the mine that is not mine space.
        int standableTotal = 0;
        int unreachableTotal = 0;
        for (int b = 0; b < bandCount; b++) {
            standableTotal += standablePerBand[b];
            unreachableTotal += unreachablePerBand[b];
        }
        int reachableTotal = standableTotal - unreachableTotal;

        // 2. Air volume reachable from the start (6-connected) and wall exposure.
        CellSet air = startWalkable ? airFlood(s, start) : new CellSet();
        long[] wallPerBand = new long[bandCount];
        long[] orePerBand = new long[bandCount];
        long[] faceTotals = new long[2];
        LongOpenHashSet exposedOre = new LongOpenHashSet();
        air.forEach(p -> {
            int px = x(p);
            int py = y(p);
            int pz = z(p);
            for (int[] f : FACES) {
                long n = pack(px + f[0], py + f[1], pz + f[2]);
                if (s.isOpen(n) || s.isFluid(n)) {
                    continue;
                }
                faceTotals[0]++;
                int b = s.bandIndex(y(n));
                if (b >= 0) {
                    wallPerBand[b]++;
                }
                if (s.ore().containsKey(n)) {
                    faceTotals[1]++;
                    exposedOre.add(n);
                    if (b >= 0) {
                        orePerBand[b]++;
                    }
                }
            }
        });
        long wallFaces = faceTotals[0];
        long oreFaces = faceTotals[1];
        Map<String, Integer> exposedByType = new HashMap<>();
        for (long n : exposedOre) {
            exposedByType.merge(s.ore().get(n), 1, Integer::sum);
        }

        // 3. Rush route: shortest walk from the start to the deepest reachable cell.
        long end = start;
        for (long p : dist.keySet()) {
            int py = y(p);
            int ey = y(end);
            if (py < ey || (py == ey && (dist.get(p) < dist.get(end)
                    || (dist.get(p) == dist.get(end) && p < end)))) {
                end = p;
            }
        }
        LongArrayList route = new LongArrayList();
        if (startWalkable) {
            long cur = end;
            route.add(cur);
            while (cur != start) {
                cur = parent.get(cur);
                route.add(cur);
            }
            java.util.Collections.reverse(route);
        }
        int[] routeStepsPerBand = new int[bandCount];
        for (long p : route) {
            int b = s.bandIndex(y(p));
            if (b >= 0) {
                routeStepsPerBand[b]++;
            }
        }
        int r = params.interestRadius();
        LongArrayList exposedOreList = new LongArrayList(exposedOre);
        exposedOreList.sort(null);
        Grid routeGrid = new Grid(route);
        Grid oreGrid = new Grid(exposedOreList);
        Grid mobGrid = new Grid(s.mobs());
        Grid lootGrid = new Grid(s.loot());
        Grid landGrid = new Grid(s.landmarks());
        int[] routeOre = nearCounts(s, exposedOreList, routeGrid, r);
        int[] routeMob = nearCounts(s, s.mobs(), routeGrid, r);
        int[] routeLoot = nearCounts(s, s.loot(), routeGrid, r);
        int[] routeLand = nearCounts(s, s.landmarks(), routeGrid, r);
        int emptyOre = longestEmpty(route, oreGrid, null, null, null, r);
        int emptyMob = longestEmpty(route, mobGrid, null, null, null, r);
        int emptyLoot = longestEmpty(route, lootGrid, null, null, null, r);
        int emptyLand = longestEmpty(route, landGrid, null, null, null, r);
        int emptyAny = longestEmpty(route, oreGrid, mobGrid, lootGrid, landGrid, r);

        java.util.List<String> bandIds = new java.util.ArrayList<>();
        for (CensusSnapshot.Band b : s.bands()) {
            bandIds.add(b.id());
        }
        return new CensusReport(
                s.chunksScanned(), s.openCount(), s.fluidCount(), s.ore().size(),
                s.mobs().size(), s.loot().size(), s.landmarks().size(),
                standableTotal, reachableTotal, standablePerBand, unreachablePerBand, bandIds,
                wallFaces, oreFaces, wallPerBand, orePerBand, exposedByType,
                startWalkable, Math.max(0, route.size() - 1), routeStepsPerBand,
                new int[] {x(end), y(end), z(end)}, params.walkBlocksPerSecond(),
                routeOre, routeMob, routeLoot, routeLand,
                emptyAny, emptyOre, emptyMob, emptyLoot, emptyLand);
    }

    // ---- walking ----

    private static boolean standable(CensusSnapshot s, long p) {
        if (!s.isOpen(p) || !s.isOpen(pack(x(p), y(p) + 1, z(p)))) {
            return false;
        }
        long below = pack(x(p), y(p) - 1, z(p));
        return !s.isOpen(below) && !s.isFluid(below);
    }

    private static boolean clearColumn(CensusSnapshot s, int x, int y, int z) {
        return s.isOpen(pack(x, y, z)) && s.isOpen(pack(x, y + 1, z));
    }

    private static void walk(CensusSnapshot s, long start, Long2LongOpenHashMap parent,
            Long2IntOpenHashMap dist) {
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        dist.put(start, 0);
        queue.enqueue(start);
        while (!queue.isEmpty()) {
            long cur = queue.dequeueLong();
            int cx = x(cur);
            int cy = y(cur);
            int cz = z(cur);
            int cd = dist.get(cur);
            for (int[] d : DIRS) {
                for (int dy = -1; dy <= 1; dy++) {
                    long next = pack(cx + d[0], cy + dy, cz + d[1]);
                    if (dist.containsKey(next) || !standable(s, next)) {
                        continue;
                    }
                    if (dy > 0 && !s.isOpen(pack(cx, cy + 2, cz))) {
                        continue;
                    }
                    if (d[0] != 0 && d[1] != 0) {
                        int crossing = Math.max(cy, cy + dy);
                        if (!clearColumn(s, cx + d[0], crossing, cz)
                                || !clearColumn(s, cx, crossing, cz + d[1])) {
                            continue;
                        }
                    }
                    dist.put(next, cd + 1);
                    parent.put(next, cur);
                    queue.enqueue(next);
                }
            }
        }
    }

    private static CellSet airFlood(CensusSnapshot s, long start) {
        CellSet seen = new CellSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        seen.add(x(start), y(start), z(start));
        queue.enqueue(start);
        while (!queue.isEmpty()) {
            long cur = queue.dequeueLong();
            for (int[] f : FACES) {
                int nx = x(cur) + f[0];
                int ny = y(cur) + f[1];
                int nz = z(cur) + f[2];
                if (s.isOpen(pack(nx, ny, nz)) && seen.add(nx, ny, nz)) {
                    queue.enqueue(pack(nx, ny, nz));
                }
            }
        }
        return seen;
    }

    // ---- spatial interest ----

    /** Coarse 8-block hash grid of points for radius queries. */
    private static final class Grid {
        private final Long2ObjectOpenHashMap<LongArrayList> cells = new Long2ObjectOpenHashMap<>();

        Grid(LongArrayList points) {
            for (long p : points) {
                cells.computeIfAbsent(key(x(p), y(p), z(p)), k -> new LongArrayList()).add(p);
            }
        }

        private static long key(int x, int y, int z) {
            return pack(x >> 3, y >> 3, z >> 3);
        }

        boolean anyWithin(int px, int py, int pz, int r) {
            return countWithin(px, py, pz, r, true) > 0;
        }

        private int countWithin(int px, int py, int pz, int r, boolean stopAtFirst) {
            int cx = px >> 3;
            int cy = py >> 3;
            int cz = pz >> 3;
            int span = (r + 7) >> 3;
            int count = 0;
            for (int dx = -span; dx <= span; dx++) {
                for (int dy = -span; dy <= span; dy++) {
                    for (int dz = -span; dz <= span; dz++) {
                        LongArrayList list = cells.get(pack(cx + dx, cy + dy, cz + dz));
                        if (list == null) {
                            continue;
                        }
                        for (long q : list) {
                            long ddx = x(q) - px;
                            long ddy = y(q) - py;
                            long ddz = z(q) - pz;
                            if (ddx * ddx + ddy * ddy + ddz * ddz <= (long) r * r) {
                                count++;
                                if (stopAtFirst) {
                                    return count;
                                }
                            }
                        }
                    }
                }
            }
            return count;
        }
    }

    /** Per band: how many of the points lie within r of any route cell. */
    private static int[] nearCounts(CensusSnapshot s, LongArrayList points, Grid routeGrid, int r) {
        int[] out = new int[s.bands().size()];
        for (long p : points) {
            if (routeGrid.anyWithin(x(p), y(p), z(p), r)) {
                int b = s.bandIndex(y(p));
                if (b >= 0) {
                    out[b]++;
                }
            }
        }
        return out;
    }

    /** Longest run of consecutive route cells with none of the given grids near. */
    private static int longestEmpty(LongArrayList route, Grid a, Grid b, Grid c, Grid d, int r) {
        int best = 0;
        int run = 0;
        for (long p : route) {
            int px = x(p);
            int py = y(p);
            int pz = z(p);
            boolean near = a.anyWithin(px, py, pz, r)
                    || (b != null && b.anyWithin(px, py, pz, r))
                    || (c != null && c.anyWithin(px, py, pz, r))
                    || (d != null && d.anyWithin(px, py, pz, r));
            run = near ? 0 : run + 1;
            best = Math.max(best, run);
        }
        return best;
    }
}
