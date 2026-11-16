package com.dwurdy.dawnmine.cave;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole mine's cave terrain: {@link CavePlanner#LAYERS} stacked layers, each spanning the
 * shell, joined by {@value #DESCENTS_PER_PAIR} staircases per layer pair. Pure and deterministic.
 *
 * <p>Placement: each layer's staircases sit at a fixed, predictable spot - evenly spread around
 * the hub by compass direction, as far out as the smaller of this layer and the one below it can
 * safely hold (see {@link #fixedRadialTops}) - not found only by exploring. A player who picks a
 * direction and walks knows a way down exists. Each layer pair's 8 angles are rotated a further
 * 1/16 turn from the pair above it, so a landing from one pair never sits directly in line, at a
 * similar radius, with the next pair's own outgoing staircase in the same direction.
 *
 * <p>A staircase is a 3-wide, {@value #TUBE_HEIGHT}-high tunnel whose floor falls by exactly one
 * block per forward step (never rounded, never two steps at once), level for {@value #FLAT}
 * steps at each end, ending in an open column of the layer below. Both layers keep a pinned,
 * level landing around each end and are closed around the tunnel in between, so the tunnel never
 * undercuts a floor or opens onto a cliff.
 */
public final class CaveStack {
    /** A real, fixed, findable staircase down from every layer, spread evenly around the hub -
     * not one organic "found while exploring" route, so a player can head in any direction and
     * know a way down exists roughly that far out. */
    public static final int DESCENTS_PER_PAIR = 8;
    public static final int TUBE_HEIGHT = 5;
    public static final int FLAT = 12;
    /** Every staircase steps down by exactly one block per forward step - never a fractional or
     * rounded drop - so it is always a real, walkable staircase, never a ramp that can round two
     * steps together into an awkward or impassable ledge. */
    private static final double SLOPE = 1.0;
    private static final int EXCL_FROM = FLAT + 4;
    private static final int EXCL_RADIUS = 3;
    private static final int PIN_RADIUS = 8;
    private static final int PIN_HEIGHT = 6;
    private static final double PIN_BLEND = 90.0;
    private static final int EDGE_MARGIN = 40;
    private static final int ATTEMPTS = 8;

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

    /** One stepped-down tread of a staircase: the floor block to replace with an actual stair
     * block, and the horizontal direction of travel (exactly one of dx/dz is +-1), so
     * {@code CaveTerrain} (which owns all actual block-state decisions) can orient it correctly.
     * Kept Minecraft-type-free like the rest of this package - {@code CaveTerrain} does the
     * {@code Direction}/{@code BlockState} translation. */
    public record StairTread(int floor, int dx, int dz) { }

    private final CaveSheet[] sheets;
    private final List<Descent> descents;
    private final Map<Long, int[]> tubeColumns;
    private final Map<Long, Integer> dropperLandings;
    private final Map<Long, List<StairTread>> stairTreads;

    private CaveStack(CaveSheet[] sheets, List<Descent> descents) {
        this.sheets = sheets;
        this.descents = List.copyOf(descents);
        this.tubeColumns = buildTubeColumns(descents);
        this.dropperLandings = buildDropperLandings(descents);
        this.stairTreads = buildStairTreads(descents);
    }

    /** Every stair tread at this column (almost always 0 or 1, but a different layer pair's
     * descent can rarely cross the same horizontal column at a different height - same reason
     * {@link #tubeSpans} returns a list rather than one value, not a single value that would
     * silently lose one of them). Never null; empty when no descent steps down through here. */
    public List<StairTread> stairTreadsAt(int x, int z) {
        return stairTreads.getOrDefault(key(x, z), List.of());
    }

    private static Map<Long, List<StairTread>> buildStairTreads(List<Descent> descents) {
        Map<Long, List<StairTread>> out = new HashMap<>();
        for (Descent d : descents) {
            int n = d.length();
            for (int i = 1; i < n; i++) {
                if (d.floors()[i] == d.floors()[i - 1]) {
                    continue; // a flat landing step - leave its floor as plain rock
                }
                int dx = Integer.compare(d.xs()[i], d.xs()[i - 1]);
                int dz = Integer.compare(d.zs()[i], d.zs()[i - 1]);
                // Lay the tread across the tube's full width, same lateral axis buildTubeColumns
                // uses, so the staircase reads as a staircase across all 3 lanes, not just the
                // one a player happens to be centred on.
                int lateralX = dx != 0 ? 0 : 1;
                int lateralZ = dx != 0 ? 1 : 0;
                for (int l = -1; l <= 1; l++) {
                    StairTread tread = new StairTread(d.floors()[i], dx, dz);
                    List<StairTread> at = out.computeIfAbsent(
                            key(d.xs()[i] + l * lateralX, d.zs()[i] + l * lateralZ), k -> new ArrayList<>());
                    // Only a genuine duplicate (same floor, e.g. this same descent's own lateral
                    // spread touching itself on a turn) is skipped - a different floor at the
                    // same column is a different layer pair's descent crossing here and must
                    // keep its own tread, not silently lose it to whichever pair ran last.
                    if (at.stream().noneMatch(t -> t.floor() == tread.floor())) {
                        at.add(tread);
                    }
                }
            }
        }
        return out;
    }

    /** M12e: the bottom floor Y of a dropper shaft landing in this column, or null. A dropper's
     * landing gets a shallow water pool (fall-damage-safe) instead of the dry floor other
     * connectors get. */
    public Integer dropperLandingFloor(int x, int z) {
        return dropperLandings.get(key(x, z));
    }

    private static Map<Long, Integer> buildDropperLandings(List<Descent> descents) {
        Map<Long, Integer> out = new HashMap<>();
        for (Descent d : descents) {
            if (!"dropper".equals(d.name())) {
                continue;
            }
            int n = d.length() - 1;
            out.put(key(d.xs()[n], d.zs()[n]), d.floors()[n]);
        }
        return out;
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
        for (int k = 0; k < layers; k++) {
            CavePlanner.Fields fk = fields[k];
            CaveSheet pass1 = CavePlanner.finish(fk, inPins, excludedIn);
            if (k == layers - 1) {
                finals[k] = pass1;
                break;
            }
            fields[k + 1] = CavePlanner.fields(specs[k + 1], seed);
            CaveSheet next0 = CavePlanner.finish(fields[k + 1], List.of(), new BitSet());

            int[][] tops = fixedRadialTops(pass1, specs[k], specs[k + 1],
                    seed + k * 31L + attempt * 1_000_003L, k, k == 0);
            List<Descent> pair = new ArrayList<>();
            List<CavePlanner.Pin> outPins = new ArrayList<>();
            List<CavePlanner.Pin> nextPins = new ArrayList<>();
            BitSet excludedOut = new BitSet();
            BitSet excludedNext = new BitSet();
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
        }
        return new CaveStack(finals, all);
    }

    /** Layer 0 keeps clear of the hub box. */
    private static boolean hubBox(int layer) {
        return layer == 0;
    }

    /** Picks {@link #DESCENTS_PER_PAIR} staircase tops, one per compass direction evenly spread
     * around the hub at a fixed radius - not found by exploring, but a real, predictable spot a
     * player can walk straight toward. The radius is clamped to what BOTH this layer and the one
     * below it (per {@code CavePlanner.LAYER_EXTENT_SCALE}, each layer can be a different size)
     * can actually hold - a top placed near this layer's own edge but beyond the smaller layer
     * below leaves no landing within reach, since every candidate would sit far outside the
     * slope-distance window {@code buildDescent} requires. */
    private static int[][] fixedRadialTops(CaveSheet s, CavePlanner.Spec spec, CavePlanner.Spec nextSpec,
                                           long seed, int pairIndex, boolean hubLayer) {
        // Always as far out as the smaller of the two layers safely allows - a fixed numeric
        // target (an earlier version of this method had one) is never reachable anyway once a
        // deeper layer's own shrink (CavePlanner.LAYER_EXTENT_SCALE) is accounted for, so using
        // it was misleading rather than meaningful.
        int radius = Math.min(Math.min(spec.maxX(), spec.maxZ()), Math.min(nextSpec.maxX(), nextSpec.maxZ()))
                - EDGE_MARGIN - 80;
        int[][] tops = new int[DESCENTS_PER_PAIR][];
        for (int j = 0; j < DESCENTS_PER_PAIR; j++) {
            // Rotated 1/16 turn per layer pair so a landing from the pair above never lines up,
            // at a similar radius, with this pair's own outgoing staircase in the same direction
            // - otherwise that gap can read as a short hop rather than a real leg of the journey.
            double angle = 2 * Math.PI * j / DESCENTS_PER_PAIR + pairIndex * (Math.PI / DESCENTS_PER_PAIR);
            int tx = (int) Math.round(radius * Math.cos(angle));
            int tz = (int) Math.round(radius * Math.sin(angle));
            Rng rng = new Rng(seed ^ 0x5441495253544F50L ^ (j * 0x9E3779B97F4A7C15L));
            int[] found = null;
            for (int attempt = 0; attempt < 800 && found == null; attempt++) {
                int spread = 24 + attempt / 3;
                int x = tx + rng.nextInt(spread * 2 + 1) - spread;
                int z = tz + rng.nextInt(spread * 2 + 1) - spread;
                if (x <= spec.minX() + EDGE_MARGIN || x >= spec.maxX() - EDGE_MARGIN
                        || z <= spec.minZ() + EDGE_MARGIN || z >= spec.maxZ() - EDGE_MARGIN) {
                    continue;
                }
                if (hubLayer && Math.abs(x) < 200 && Math.abs(z) < 200) {
                    continue;
                }
                if (!s.isOpen(x, z) || s.heightAt(x, z) < 6 || Rock.protectedColumn(x, z)) {
                    continue;
                }
                found = new int[] {x, z};
            }
            if (found == null) {
                throw new IllegalStateException("could not find a staircase top near angle "
                        + Math.round(Math.toDegrees(angle)) + " degrees, radius " + radius);
            }
            tops[j] = found;
        }
        return tops;
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
        double slope = SLOPE;
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
            for (int i = 0; i < len; i++) {
                if (i < FLAT) {
                    floors[i] = fA;
                } else if (i >= len - FLAT) {
                    floors[i] = fB;
                } else {
                    // Exactly one block down per step, never a rounded or fractional drop, so
                    // every staircase is genuinely walkable - any slack between a longer routed
                    // path and the real drop becomes a flat landing near the bottom, not a step
                    // that's taller or shorter than one block.
                    floors[i] = fA - Math.min(drop, i - FLAT + 1);
                }
            }
            return new Descent(layer, "stair" + j, path[0], path[1], floors);
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
