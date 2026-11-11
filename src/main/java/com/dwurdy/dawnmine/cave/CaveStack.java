package com.dwurdy.dawnmine.cave;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole mine's cave terrain: {@link CavePlanner#LAYERS} stacked layers, each spanning the
 * shell, joined by three descent tunnels per layer pair. Pure and deterministic.
 *
 * <p>Pacing: the way down each layer is placed by measured walking distance. The layer's
 * entry (the hub forecourt, or where the descents above land) to each outgoing descent is
 * about {@link #TARGET_BLOCKS} blocks of walking (a rush at 4.3 blocks/s is roughly 15 minutes),
 * and the three descents differ in length and steepness and sit hundreds of blocks apart, so
 * there are at least three different ways down.
 *
 * <p>A descent is a 3-wide, {@value #TUBE_HEIGHT}-high tunnel whose floor never falls faster
 * than 1 block per 2 steps, level for {@value #FLAT} steps at each end, ending in an open
 * column of the layer below. Both layers keep a pinned, level landing around each end and are
 * closed around the tunnel in between, so the tunnel never undercuts a floor or opens onto a cliff.
 */
public final class CaveStack {
    /** Target walking distance, entry to the way down, per layer. */
    public static final int TARGET_BLOCKS = 3600;
    public static final int DESCENTS_PER_PAIR = 3;
    public static final int TUBE_HEIGHT = 5;
    public static final int FLAT = 12;
    private static final double[] SLOPES = {0.5, 0.25, 0.4};
    private static final String[] NAMES = {"steep", "gentle", "bypass"};
    private static final int EXCL_FROM = FLAT + 4;
    private static final int EXCL_RADIUS = 3;
    private static final int PIN_RADIUS = 8;
    private static final int PIN_HEIGHT = 6;
    private static final double PIN_BLEND = 90.0;
    private static final int EDGE_MARGIN = 40;
    private static final int ATTEMPTS = 8;
    private static final int CLUSTER = 450;
    private static final int RIDGE_OFFSET = 300;
    private static final int RIDGE_HALF_THICKNESS = 5;
    private static final int RIDGE_GAP_HALF = 80;
    private static final int RIDGE_GAP_FROM_EDGE = 100;

    /** A named point to measure walking distance to (feet position). */
    public record Waypoint(String name, int x, int y, int z) { }

    /** One descent tunnel: a 4-adjacent column path with a floor y per column. */
    public record Descent(int fromLayer, String name, int[] xs, int[] zs, int[] floors) {
        public int length() { return xs.length; }
        public Waypoint top() {
            return new Waypoint("L" + fromLayer + "-" + name + "-top", xs[0], floors[0] + 1, zs[0]);
        }
        public Waypoint bottom() {
            int n = xs.length - 1;
            return new Waypoint("L" + fromLayer + "-" + name + "-bottom", xs[n], floors[n] + 1, zs[n]);
        }
    }

    private final CaveSheet[] sheets;
    private final List<Descent> descents;
    private final Map<Long, int[]> tubeColumns;

    private CaveStack(CaveSheet[] sheets, List<Descent> descents) {
        this.sheets = sheets;
        this.descents = List.copyOf(descents);
        this.tubeColumns = buildTubeColumns(descents);
    }

    public int layers() { return sheets.length; }

    public CaveSheet sheet(int layer) { return sheets[layer]; }

    public List<Descent> descents() { return descents; }

    /** Open y ranges {lo, hi, lo, hi, ...} of tunnel cells in a column, or null. */
    public int[] tubeSpans(int x, int z) {
        return tubeColumns.get(key(x, z));
    }

    /** Every column that holds tunnel cells (key as from {@link #key}). */
    public Map<Long, int[]> tubeColumns() { return tubeColumns; }

    public List<Waypoint> waypoints() {
        List<Waypoint> out = new ArrayList<>();
        for (Descent d : descents) {
            out.add(d.top());
            out.add(d.bottom());
        }
        return out;
    }

    /** Writes every open cell (layers and tunnels) into a census snapshot. */
    public void addTo(com.dwurdy.dawnmine.census.CensusSnapshot snapshot) {
        for (CaveSheet sh : sheets) {
            sh.addTo(snapshot);
        }
        for (var e : tubeColumns.entrySet()) {
            int x = (int) (e.getKey() >> 32);
            int z = (int) (long) e.getKey();
            int[] spans = e.getValue();
            for (int i = 0; i < spans.length; i += 2) {
                for (int y = spans[i]; y <= spans[i + 1]; y++) {
                    snapshot.addOpen(x, y, z);
                }
            }
        }
    }

    public static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    // ---- planning ----

    /** Plans the stack, retrying with a different (still deterministic) choice of ways down if a
     * layer cannot place or route all three: the layers themselves stay the same. */
    public static CaveStack plan(int shellChunks, long seed) {
        IllegalStateException last = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                return planOnce(shellChunks, seed, attempt);
            } catch (IllegalStateException e) {
                last = e;
            }
        }
        throw new IllegalStateException("cave stack for seed " + seed + " failed after " + ATTEMPTS
                + " attempts: " + last.getMessage(), last);
    }

    private static CaveStack planOnce(int shellChunks, long seed, int attempt) {
        int layers = CavePlanner.LAYERS;
        CavePlanner.Spec[] specs = new CavePlanner.Spec[layers];
        CavePlanner.Fields[] fields = new CavePlanner.Fields[layers];
        for (int k = 0; k < layers; k++) {
            specs[k] = CavePlanner.Spec.layer(k, shellChunks);
        }
        CaveSheet[] finals = new CaveSheet[layers];
        List<Descent> all = new ArrayList<>();

        fields[0] = CavePlanner.fields(specs[0], seed);
        List<CavePlanner.Pin> inPins = new ArrayList<>();
        BitSet excludedIn = new BitSet();
        int[][] entries = {{36, 0}};
        for (int k = 0; k < layers; k++) {
            CavePlanner.Fields fk = fields[k];
            excludedIn.or(ridge(k, fk, entries));
            CaveSheet pass1 = CavePlanner.finish(fk, inPins, excludedIn);
            if (k == layers - 1) {
                finals[k] = pass1;
                break;
            }
            fields[k + 1] = CavePlanner.fields(specs[k + 1], seed);
            CaveSheet next0 = CavePlanner.finish(fields[k + 1], List.of(), new BitSet());

            int[] dist = WalkDistance.from(pass1, entries);
            int[][] tops = chooseTops(pass1, fk, dist, seed + k * 31L + attempt * 1_000_003L, k == 0);
            List<Descent> pair = new ArrayList<>();
            List<CavePlanner.Pin> outPins = new ArrayList<>();
            List<CavePlanner.Pin> nextPins = new ArrayList<>();
            BitSet excludedOut = new BitSet();
            BitSet excludedNext = new BitSet();
            int[][] landings = new int[tops.length][];
            for (int j = 0; j < tops.length; j++) {
                Descent made = buildDescent(k, j, tops[j], pass1, next0, specs[k], specs[k + 1],
                        fk, fields[k + 1], seed + attempt * 1_000_003L, pair, hubBox(k), excludedIn);
                if (made == null) {
                    throw new IllegalStateException("could not route descent " + j + " from layer " + k);
                }
                pair.add(made);
                int n = made.length() - 1;
                outPins.add(CavePlanner.Pin.point(made.xs()[0], made.zs()[0], PIN_RADIUS,
                        made.floors()[0], PIN_HEIGHT, PIN_BLEND));
                nextPins.add(CavePlanner.Pin.point(made.xs()[n], made.zs()[n], PIN_RADIUS,
                        made.floors()[n], PIN_HEIGHT, PIN_BLEND));
                landings[j] = new int[] {made.xs()[n], made.zs()[n]};
                excludeAlong(made, specs[k], specs[k + 1], fk, fields[k + 1], excludedOut, excludedNext);
            }
            all.addAll(pair);
            List<CavePlanner.Pin> pins = new ArrayList<>(inPins);
            pins.addAll(outPins);
            BitSet excl = (BitSet) excludedIn.clone();
            excl.or(excludedOut);
            finals[k] = CavePlanner.finish(fk, pins, excl);
            fields[k] = null; // free the noise arrays once the layer is final
            inPins = nextPins;
            excludedIn = excludedNext;
            entries = landings;
        }
        return new CaveStack(finals, all);
    }

    /** A long rock ridge across the layer with one wide gap at the far end from where the layer is
     * entered, so the way down is a real journey rather than a short hop. The ridge runs across
     * the map between the entry side and the far side; alternate layers use alternate directions. */
    static BitSet ridge(int layer, CavePlanner.Fields f, int[][] entries) {
        CavePlanner.Spec sp = f.spec();
        double ex = 0;
        double ez = 0;
        int minAcross = Integer.MAX_VALUE;
        int maxAcross = Integer.MIN_VALUE;
        boolean vertical = layer % 2 == 1;
        for (int[] e : entries) {
            ex += e[0];
            ez += e[1];
            int a = vertical ? e[0] : e[1];
            minAcross = Math.min(minAcross, a);
            maxAcross = Math.max(maxAcross, a);
        }
        ex /= entries.length;
        ez /= entries.length;
        double along = vertical ? ez : ex;   // position along the ridge
        double across = vertical ? ex : ez;  // position across it
        // the ridge sits between the entries and the middle of the map, with every entry on one side
        int c = layer == 0 ? 250 : (across > 0 ? minAcross - RIDGE_OFFSET : maxAcross + RIDGE_OFFSET);
        int gap = along >= 0 ? -(RIDGE_GAP_FROM_EDGE) : RIDGE_GAP_FROM_EDGE;
        int edge = Math.min(sp.maxX(), sp.maxZ());
        gap = gap < 0 ? -edge + RIDGE_GAP_FROM_EDGE : edge - RIDGE_GAP_FROM_EDGE;
        BitSet out = new BitSet();
        for (int z = sp.minZ(); z <= sp.maxZ(); z++) {
            for (int x = sp.minX(); x <= sp.maxX(); x++) {
                int a = vertical ? z : x;
                int cr = vertical ? x : z;
                if (Math.abs(cr - c) > RIDGE_HALF_THICKNESS || Math.abs(a - gap) <= RIDGE_GAP_HALF) {
                    continue;
                }
                boolean nearEntry = false;
                for (int[] e : entries) {
                    if (Math.abs(e[0] - x) < 40 && Math.abs(e[1] - z) < 40) {
                        nearEntry = true;
                        break;
                    }
                }
                if (!nearEntry) {
                    out.set(f.index(x, z));
                }
            }
        }
        return out;
    }

    /** Layer 0 keeps clear of the hub box. */
    private static boolean hubBox(int layer) {
        return layer == 0;
    }

    /** Picks three outgoing descent tops by walking distance, hashed for variety and kept apart. */
    private static int[][] chooseTops(CaveSheet s, CavePlanner.Fields f, int[] dist, long seed,
                                      boolean hubLayer) {
        int w = s.sizeX();
        int max = 0;
        for (int v : dist) {
            max = Math.max(max, v);
        }
        int target = Math.min(TARGET_BLOCKS * 10, (int) (max * 0.9));
        int lo = (int) (target * 0.93);
        int hi = target;
        List<int[]> picked = new ArrayList<>();
        int[] spacings = {250, 150, 100};
        for (int spacing : spacings) {
            picked.clear();
            for (int j = 0; j < DESCENTS_PER_PAIR; j++) {
                long bestHash = Long.MIN_VALUE;
                int bestX = 0;
                int bestZ = 0;
                boolean found = false;
                for (int i = 0; i < dist.length; i++) {
                    int dd = dist[i];
                    if (dd < lo || dd > hi) {
                        continue;
                    }
                    int x = s.minX() + i % w;
                    int z = s.minZ() + i / w;
                    if (x < s.minX() + EDGE_MARGIN + 60 || x > s.maxX() - EDGE_MARGIN - 60
                            || z < s.minZ() + EDGE_MARGIN + 60 || z > s.maxZ() - EDGE_MARGIN - 60) {
                        continue;
                    }
                    if (hubLayer && Math.abs(x) < 200 && Math.abs(z) < 200) {
                        continue;
                    }
                    boolean apart = true;
                    for (int[] p : picked) {
                        double dd2 = StrictMath.hypot(p[0] - x, p[1] - z);
                        // the ways down sit together (so the layer below is entered in one region
                        // and its own way down is a long walk away) yet well apart from each other
                        if (dd2 < spacing || dd2 > CLUSTER) {
                            apart = false;
                            break;
                        }
                    }
                    if (!apart) {
                        continue;
                    }
                    long h = mix(seed * 0x9E3779B97F4A7C15L + i * 0xBF58476D1CE4E5B9L + j);
                    if (!found || h > bestHash) {
                        found = true;
                        bestHash = h;
                        bestX = x;
                        bestZ = z;
                    }
                }
                if (!found) {
                    break;
                }
                picked.add(new int[] {bestX, bestZ});
            }
            if (picked.size() == DESCENTS_PER_PAIR) {
                return picked.toArray(new int[0][]);
            }
        }
        throw new IllegalStateException("fewer than " + DESCENTS_PER_PAIR + " descent sites at distance "
                + target / 10 + " blocks (reachable maximum " + max / 10 + ")");
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Routes one descent from the top site to an open column of the layer below, or null. */
    private static Descent buildDescent(int layer, int j, int[] top, CaveSheet a, CaveSheet b,
            CavePlanner.Spec sa, CavePlanner.Spec sb, CavePlanner.Fields fa, CavePlanner.Fields fb,
            long seed, List<Descent> earlier, boolean avoidHub, BitSet blockedA) {
        int px = top[0];
        int pz = top[1];
        int fA = a.floorAt(px, pz);
        double slope = SLOPES[j];
        int reach = 400;
        int tried = 0;
        // candidate landing columns around the top, tried in hash order (a fixed, seed-dependent shuffle)
        List<int[]> candidates = new ArrayList<>();
        for (int z = Math.max(b.minZ() + EDGE_MARGIN, pz - reach); z <= Math.min(b.maxZ() - EDGE_MARGIN, pz + reach); z += 3) {
            for (int x = Math.max(b.minX() + EDGE_MARGIN, px - reach); x <= Math.min(b.maxX() - EDGE_MARGIN, px + reach); x += 3) {
                if (b.isOpen(x, z) && b.heightAt(x, z) >= 6) {
                    candidates.add(new int[] {x, z});
                }
            }
        }
        long[] order = new long[candidates.size()];
        for (int c = 0; c < order.length; c++) {
            int[] q = candidates.get(c);
            long h = mix(seed * 31 + layer * 1000003L + j * 7919L + q[0] * 73856093L + q[1] * 19349663L);
            order[c] = (h & ~0xFFFFFL) | c; // hash in the high bits, candidate index in the low 20
        }
        java.util.Arrays.sort(order);
        for (int o = order.length - 1; o >= 0; o--) {
            int[] q = candidates.get((int) (order[o] & 0xFFFFFL));
            int fB = b.floorAt(q[0], q[1]);
            int drop = fA - fB;
            if (drop < 8) {
                continue;
            }
            int need = 2 * FLAT + (int) Math.ceil(drop / slope);
            double e = StrictMath.hypot(q[0] - px, q[1] - pz);
            if (e < 0.3 * need || e > 0.8 * need) {
                continue;
            }
            if (tried++ > 400) {
                break;
            }
            int[][] path = routePath(px, pz, q[0], q[1], need);
            if (path == null || !pathClear(path, a, b, sa, avoidHub, earlier)
                    || crossesBlocked(path, blockedA, fa)) {
                continue;
            }
            int len = path[0].length;
            int[] floors = new int[len];
            int m = len - 2 * FLAT;
            for (int i = 0; i < len; i++) {
                if (i < FLAT) {
                    floors[i] = fA;
                } else if (i >= len - FLAT) {
                    floors[i] = fB;
                } else {
                    floors[i] = fA - (int) Math.round(drop * (i - FLAT + 1) / (double) (m + 1));
                }
            }
            return new Descent(layer, NAMES[j], path[0], path[1], floors);
        }
        return null;
    }

    /** True when any tunnel column lies in a closed column of the upper layer (its ridge or another
     * tunnel's closed margin), so a tunnel can never cut through a ridge. */
    private static boolean crossesBlocked(int[][] path, BitSet blocked, CavePlanner.Fields fa) {
        for (int i = 0; i < path[0].length; i++) {
            if (blocked.get(fa.index(path[0][i], path[1][i]))) {
                return true;
            }
        }
        return false;
    }

    /** Edge, hub and earlier-tunnel clearance for a candidate path. */
    private static boolean pathClear(int[][] path, CaveSheet a, CaveSheet b, CavePlanner.Spec sa,
                                     boolean avoidHub, List<Descent> earlier) {
        int n = path[0].length;
        for (int i = 0; i < n; i++) {
            int x = path[0][i];
            int z = path[1][i];
            if (!a.contains(x - 4, z - 4) || !a.contains(x + 4, z + 4)) {
                return false;
            }
            if (x <= a.minX() + EDGE_MARGIN || x >= a.maxX() - EDGE_MARGIN
                    || z <= a.minZ() + EDGE_MARGIN || z >= a.maxZ() - EDGE_MARGIN) {
                return false;
            }
            if (avoidHub && Math.abs(x) < 60 && Math.abs(z) < 60) {
                return false;
            }
        }
        // no tight self-approach: columns far apart along the path stay at least 8 apart in plan
        for (int i = 0; i < n; i++) {
            for (int k = i + 16; k < n; k++) {
                int dx = path[0][i] - path[0][k];
                int dz = path[1][i] - path[1][k];
                if (dx * dx + dz * dz < 64) {
                    return false;
                }
            }
        }
        for (Descent e : earlier) {
            for (int i = 0; i < n; i++) {
                for (int k = 0; k < e.length(); k++) {
                    int dx = path[0][i] - e.xs()[k];
                    int dz = path[1][i] - e.zs()[k];
                    if (dx * dx + dz * dz < 100) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** A 4-adjacent column path from P to Q with at least {@code need} columns, meandering if needed. */
    private static int[][] routePath(int px, int pz, int qx, int qz, int need) {
        double dx = qx - px;
        double dz = qz - pz;
        double e = StrictMath.hypot(dx, dz);
        double nx = -dz / e;
        double nz = dx / e;
        for (double amp = 0; amp <= 0.45 * e; amp += 3) {
            int steps = (int) Math.ceil(e * 6) + 1;
            List<int[]> cells = new ArrayList<>();
            int cx = px;
            int cz = pz;
            cells.add(new int[] {cx, cz});
            for (int s = 1; s <= steps; s++) {
                double t = s / (double) steps;
                double off = amp * StrictMath.sin(2 * Math.PI * 1.5 * t) * StrictMath.sin(Math.PI * t);
                int tx = s == steps ? qx : (int) Math.round(px + dx * t + nx * off);
                int tz = s == steps ? qz : (int) Math.round(pz + dz * t + nz * off);
                while (cx != tx || cz != tz) {
                    if (cx != tx) {
                        cx += Integer.compare(tx, cx);
                    } else {
                        cz += Integer.compare(tz, cz);
                    }
                    cells.add(new int[] {cx, cz});
                }
            }
            if (cells.size() >= need && !revisits(cells)) {
                int[] xs = new int[cells.size()];
                int[] zs = new int[cells.size()];
                for (int i = 0; i < xs.length; i++) {
                    xs[i] = cells.get(i)[0];
                    zs[i] = cells.get(i)[1];
                }
                return new int[][] {xs, zs};
            }
        }
        return null;
    }

    /** True when the path steps back onto a column it already used. */
    private static boolean revisits(List<int[]> cells) {
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        for (int[] c : cells) {
            if (!seen.add(key(c[0], c[1]))) {
                return true;
            }
        }
        return false;
    }

    private static boolean adjacent(List<int[]> cells) {
        for (int i = 1; i < cells.size(); i++) {
            int d = Math.abs(cells.get(i)[0] - cells.get(i - 1)[0])
                    + Math.abs(cells.get(i)[1] - cells.get(i - 1)[1]);
            if (d != 1) {
                return false;
            }
        }
        return true;
    }

    /** Closes columns around the middle of a tunnel in each layer it passes through. */
    private static void excludeAlong(Descent dsc, CavePlanner.Spec sa, CavePlanner.Spec sb,
            CavePlanner.Fields fa, CavePlanner.Fields fb, BitSet exclA, BitSet exclB) {
        int n = dsc.length();
        for (int i = EXCL_FROM; i < n - EXCL_FROM; i++) {
            int y = dsc.floors()[i];
            boolean inA = y + TUBE_HEIGHT >= sa.bandMinY() - 2;
            boolean inB = y <= sb.bandMaxY() + 2;
            for (int dz = -EXCL_RADIUS; dz <= EXCL_RADIUS; dz++) {
                for (int dx = -EXCL_RADIUS; dx <= EXCL_RADIUS; dx++) {
                    int x = dsc.xs()[i] + dx;
                    int z = dsc.zs()[i] + dz;
                    if (inA && x >= sa.minX() && x <= sa.maxX() && z >= sa.minZ() && z <= sa.maxZ()) {
                        exclA.set(fa.index(x, z));
                    }
                    if (inB && x >= sb.minX() && x <= sb.maxX() && z >= sb.minZ() && z <= sb.maxZ()) {
                        exclB.set(fb.index(x, z));
                    }
                }
            }
        }
    }

    // ---- tunnel cells ----

    private static Map<Long, int[]> buildTubeColumns(List<Descent> descents) {
        Map<Long, List<int[]>> raw = new HashMap<>();
        for (Descent d : descents) {
            int n = d.length();
            for (int i = 0; i < n; i++) {
                int hx = i + 1 < n ? d.xs()[i + 1] - d.xs()[i] : d.xs()[i] - d.xs()[i - 1];
                int lateralX = hx != 0 ? 0 : 1;
                int lateralZ = hx != 0 ? 1 : 0;
                int lo = d.floors()[i] + 1;
                int hi = d.floors()[i] + TUBE_HEIGHT;
                for (int l = -1; l <= 1; l++) {
                    long k = key(d.xs()[i] + l * lateralX, d.zs()[i] + l * lateralZ);
                    raw.computeIfAbsent(k, kk -> new ArrayList<>()).add(new int[] {lo, hi});
                }
            }
        }
        Map<Long, int[]> out = new HashMap<>();
        for (var e : raw.entrySet()) {
            List<int[]> r = e.getValue();
            r.sort((p, q) -> Integer.compare(p[0], q[0]));
            List<int[]> merged = new ArrayList<>();
            for (int[] x : r) {
                if (!merged.isEmpty() && x[0] <= merged.get(merged.size() - 1)[1] + 1) {
                    merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], x[1]);
                } else {
                    merged.add(new int[] {x[0], x[1]});
                }
            }
            int[] flat = new int[merged.size() * 2];
            for (int i = 0; i < merged.size(); i++) {
                flat[2 * i] = merged.get(i)[0];
                flat[2 * i + 1] = merged.get(i)[1];
            }
            out.put(e.getKey(), flat);
        }
        return out;
    }
}
