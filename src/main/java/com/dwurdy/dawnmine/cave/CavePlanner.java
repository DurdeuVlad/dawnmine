package com.dwurdy.dawnmine.cave;

import com.dwurdy.dawnmine.topology.HubLayout;

/**
 * Plans one walkable cave layer from a seed. Pure and deterministic: the same spec and seed
 * always give the same {@link CaveSheet}. Walkability is built in rather than repaired:
 * <ol>
 *   <li>floors come from smooth noise, then a chamfer pass lowers any peak so neighbouring
 *       floors never differ by more than 1 (no cliffs, no pits a player cannot climb out of);</li>
 *   <li>open space is the union of noise caverns and spaghetti tunnels (the zero lines of a
 *       2D noise, which wind as long connected curves), always at least
 *       {@link CaveSheet#MIN_HEIGHT} cells high;</li>
 *   <li>the hub box is closed rock; a pinned forecourt east of the hub gateway is open and
 *       floored at the hub floor so the hub opens straight into the cave;</li>
 *   <li>open regions are labelled; tiny ones are filled in and every other one is joined to
 *       the forecourt region by a shortest 3-wide channel, so there is exactly one region.</li>
 * </ol>
 */
public final class CavePlanner {
    /** Hub floor block y and the forecourt pin, taken from the hub layout. */
    private static final int PIN_FLOOR = HubLayout.FEET_Y - 1;
    private static final int PIN_X0 = 25;
    private static final int PIN_X1 = 36;
    private static final int PIN_RADIUS = 8;
    private static final int PIN_HEIGHT = 8;
    private static final double BLEND = 40.0;
    private static final int HUB_EXCLUDE = 24;
    /** Open regions smaller than this many columns are filled in instead of joined. */
    private static final int MIN_REGION = 60;
    private static final int CHANNEL_HEIGHT = 5;

    /** Extent and vertical limits of one layer. Bounds are inclusive block coordinates. */
    public record Spec(int minX, int maxX, int minZ, int maxZ,
            int floorBase, int floorAmp, int maxHeight, int bandMinY, int bandMaxY) {
        /** The shared mine's first layer: inside the 64-chunk shell with a rock margin. */
        public static Spec standard() {
            return new Spec(-1018, 1018, -1018, 1018, 44, 12, 22, 24, 100);
        }

        /** The layer sized to a bedrock shell of the given radius in chunks (6 blocks of rock margin). */
        public static Spec forShell(int shellRadiusChunks) {
            int edge = shellRadiusChunks * 16 - 6;
            return new Spec(-edge, edge, -edge, edge, 44, 12, 22, 24, 100);
        }

        /** A smaller square of the same layer, for tests. */
        public Spec withRadius(int radius) {
            return new Spec(-radius, radius, -radius, radius, floorBase, floorAmp, maxHeight,
                    bandMinY, bandMaxY);
        }
    }

    private CavePlanner() { }

    public static CaveSheet plan(Spec spec, long seed) {
        int w = spec.maxX() - spec.minX() + 1;
        int d = spec.maxZ() - spec.minZ() + 1;
        int[] floor = new int[w * d];
        int[] height = new int[w * d];
        Noise2D floorLow = new Noise2D(seed ^ 0x51A5E1L);
        Noise2D floorHigh = new Noise2D(seed ^ 0x7F3C99L);
        Noise2D cavern = new Noise2D(seed ^ 0xCA7E41L);
        Noise2D tunA = new Noise2D(seed ^ 0x70AA11L);
        Noise2D tunAWidth = new Noise2D(seed ^ 0x70AA22L);
        Noise2D tunB = new Noise2D(seed ^ 0x70BB11L);
        Noise2D tunBWidth = new Noise2D(seed ^ 0x70BB22L);
        Noise2D pillar = new Noise2D(seed ^ 0x9111A2L);

        int floorMin = spec.bandMinY() + 1;
        int floorMax = spec.bandMaxY() - CaveSheet.MIN_HEIGHT - 1;
        for (int z = spec.minZ(); z <= spec.maxZ(); z++) {
            for (int x = spec.minX(); x <= spec.maxX(); x++) {
                int i = (z - spec.minZ()) * w + (x - spec.minX());
                double pd = pinDistance(x, z);
                double s = pd <= 0 ? 0 : smooth(Math.min(1.0, pd / BLEND));

                double fn = spec.floorBase() + spec.floorAmp()
                        * (0.7 * floorLow.fbm(x / 180.0, z / 180.0, 4, 2.0, 0.5)
                        + 0.3 * floorHigh.fbm(x / 60.0 + 100, z / 60.0, 3, 2.0, 0.5));
                fn = Math.max(floorMin, Math.min(floorMax, fn));
                floor[i] = (int) Math.round(PIN_FLOOR + (fn - PIN_FLOOR) * s);

                double c = cavern.fbm((x + 500) / 110.0, (z - 300) / 110.0, 4, 2.0, 0.5);
                double hc = smooth(clamp01((c - 0.24) / 0.25));
                double raw = hc > 0 ? 4 + hc * (spec.maxHeight() - 4) : 0;
                raw = Math.max(raw, tunnel(tunA, tunAWidth, x, z, 0, 0));
                raw = Math.max(raw, tunnel(tunB, tunBWidth, x, z, 4000, -2500));
                if (raw > 0 && pd > 4 && pillar.fbm(x / 14.0, z / 14.0, 2, 2.0, 0.5) > 0.55) {
                    raw = 0; // a rock pillar
                }
                double blended = PIN_HEIGHT + (raw - PIN_HEIGHT) * s;
                height[i] = (int) Math.round(blended);
            }
        }

        chamfer(floor, w, d);

        for (int z = spec.minZ(); z <= spec.maxZ(); z++) {
            for (int x = spec.minX(); x <= spec.maxX(); x++) {
                int i = (z - spec.minZ()) * w + (x - spec.minX());
                int h = Math.min(height[i], spec.bandMaxY() - floor[i] - 1);
                if (h < CaveSheet.MIN_HEIGHT || forbidden(x, z, spec)) {
                    h = 0;
                }
                height[i] = h;
            }
        }

        // Rock between forecourt and a floor that was lowered out of the pin would be a bug.
        int pinIndex = (0 - spec.minZ()) * w + (PIN_X1 - spec.minX());
        if (floor[pinIndex] != PIN_FLOOR || height[pinIndex] <= 0) {
            throw new IllegalStateException("forecourt pin was not kept: floor=" + floor[pinIndex]
                    + " height=" + height[pinIndex]);
        }

        joinRegions(spec, floor, height, w, d, pinIndex);

        byte[] packed = new byte[w * d];
        for (int i = 0; i < packed.length; i++) {
            packed[i] = (byte) height[i];
        }
        return new CaveSheet(spec.minX(), spec.minZ(), w, d, floor, packed);
    }

    // ---- terrain fields ----

    private static double tunnel(Noise2D line, Noise2D width, int x, int z, int ox, int oz) {
        double t = line.fbm((x + ox) / 130.0, (z + oz) / 130.0, 3, 2.0, 0.5);
        double wn = 0.5 + 0.5 * width.fbm((x + ox) / 200.0, (z + oz) / 200.0, 2, 2.0, 0.5);
        double half = 0.014 + 0.026 * wn;
        double a = Math.abs(t);
        if (a >= half) {
            return 0;
        }
        return CaveSheet.MIN_HEIGHT + 3 * Math.sqrt(1 - a / half);
    }

    /** Distance outside the forecourt pin (0 inside it); large to the west of it. */
    private static double pinDistance(int x, int z) {
        double t = Math.max(0, Math.min(1.0, (x - PIN_X0) / (double) (PIN_X1 - PIN_X0)));
        double px = PIN_X0 + (PIN_X1 - PIN_X0) * t;
        double dist = Math.hypot(x - px, z);
        if (x >= PIN_X0 && dist <= PIN_RADIUS) {
            return 0;
        }
        return Math.max(1e-6, dist - PIN_RADIUS);
    }

    private static boolean forbidden(int x, int z, Spec spec) {
        boolean inHub = Math.abs(x) <= HUB_EXCLUDE && Math.abs(z) <= HUB_EXCLUDE;
        return inHub || x == spec.minX() || x == spec.maxX() || z == spec.minZ() || z == spec.maxZ();
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double clamp01(double t) {
        return Math.max(0, Math.min(1, t));
    }

    /** Lowers floors so every column is within 1 of each of its 8 neighbours (two-pass chamfer). */
    private static void chamfer(int[] f, int w, int d) {
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                int i = z * w + x;
                int m = f[i];
                if (x > 0) { m = Math.min(m, f[i - 1] + 1); }
                if (z > 0) {
                    m = Math.min(m, f[i - w] + 1);
                    if (x > 0) { m = Math.min(m, f[i - w - 1] + 1); }
                    if (x < w - 1) { m = Math.min(m, f[i - w + 1] + 1); }
                }
                f[i] = m;
            }
        }
        for (int z = d - 1; z >= 0; z--) {
            for (int x = w - 1; x >= 0; x--) {
                int i = z * w + x;
                int m = f[i];
                if (x < w - 1) { m = Math.min(m, f[i + 1] + 1); }
                if (z < d - 1) {
                    m = Math.min(m, f[i + w] + 1);
                    if (x < w - 1) { m = Math.min(m, f[i + w + 1] + 1); }
                    if (x > 0) { m = Math.min(m, f[i + w - 1] + 1); }
                }
                f[i] = m;
            }
        }
    }

    // ---- connectivity ----

    /** Fills tiny open regions and joins every other region to the forecourt region. */
    private static void joinRegions(Spec spec, int[] floor, int[] height, int w, int d, int pinIndex) {
        int n = w * d;
        int[] label = new int[n];
        int[] queue = new int[n];
        int labels = labelRegions(height, label, queue, w, d);
        int mainLabel = label[pinIndex];
        int[] size = new int[labels + 1];
        for (int i = 0; i < n; i++) {
            if (label[i] > 0) {
                size[label[i]]++;
            }
        }
        boolean needJoin = false;
        for (int i = 0; i < n; i++) {
            int l = label[i];
            if (l > 0 && l != mainLabel) {
                if (size[l] < MIN_REGION) {
                    height[i] = 0;
                    label[i] = 0;
                } else {
                    needJoin = true;
                }
            }
        }
        if (!needJoin) {
            return;
        }

        // One breadth-first search from the main region over every column that may be opened.
        int[] parent = new int[n];
        int[] dist = new int[n];
        java.util.Arrays.fill(dist, -1);
        int head = 0;
        int tail = 0;
        for (int i = 0; i < n; i++) {
            if (label[i] == mainLabel) {
                dist[i] = 0;
                parent[i] = -1;
                queue[tail++] = i;
            }
        }
        while (head < tail) {
            int cur = queue[head++];
            int x = cur % w;
            int z = cur / w;
            for (int k = 0; k < 4; k++) {
                int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0);
                int nz = z + (k == 2 ? 1 : k == 3 ? -1 : 0);
                if (nx < 0 || nz < 0 || nx >= w || nz >= d) {
                    continue;
                }
                int ni = nz * w + nx;
                if (dist[ni] >= 0 || forbidden(spec.minX() + nx, spec.minZ() + nz, spec)) {
                    continue;
                }
                dist[ni] = dist[cur] + 1;
                parent[ni] = cur;
                queue[tail++] = ni;
            }
        }

        // For each other region (by label), cut a channel from its nearest cell back to the main region.
        int[] nearest = new int[labels + 1];
        java.util.Arrays.fill(nearest, -1);
        for (int i = 0; i < n; i++) {
            int l = label[i];
            if (l > 0 && l != mainLabel && dist[i] >= 0
                    && (nearest[l] < 0 || dist[i] < dist[nearest[l]])) {
                nearest[l] = i;
            }
        }
        for (int l = 1; l <= labels; l++) {
            if (l == mainLabel || nearest[l] < 0) {
                continue;
            }
            for (int at = nearest[l]; at >= 0 && label[at] != mainLabel; at = parent[at]) {
                carveChannel(spec, floor, height, w, d, at);
            }
        }

        // Anything still not in the main region (blocked by the hub box or edge) is filled in.
        labels = labelRegions(height, label, queue, w, d);
        mainLabel = label[pinIndex];
        for (int i = 0; i < n; i++) {
            if (label[i] > 0 && label[i] != mainLabel) {
                height[i] = 0;
            }
        }
    }

    /** Opens a 3-wide channel centred on a column, skipping forbidden columns. */
    private static void carveChannel(Spec spec, int[] floor, int[] height, int w, int d, int at) {
        int cx = at % w;
        int cz = at / w;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (Math.abs(dx) + Math.abs(dz) > 1) {
                    continue;
                }
                int nx = cx + dx;
                int nz = cz + dz;
                if (nx < 0 || nz < 0 || nx >= w || nz >= d
                        || forbidden(spec.minX() + nx, spec.minZ() + nz, spec)) {
                    continue;
                }
                int ni = nz * w + nx;
                int h = Math.min(CHANNEL_HEIGHT, spec.bandMaxY() - floor[ni] - 1);
                if (height[ni] < h && h >= CaveSheet.MIN_HEIGHT) {
                    height[ni] = h;
                }
            }
        }
    }

    /** Labels 4-connected open regions 1..n in raster order; returns n. */
    private static int labelRegions(int[] height, int[] label, int[] queue, int w, int d) {
        java.util.Arrays.fill(label, 0);
        int next = 0;
        for (int start = 0; start < height.length; start++) {
            if (height[start] <= 0 || label[start] != 0) {
                continue;
            }
            next++;
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            label[start] = next;
            while (head < tail) {
                int cur = queue[head++];
                int x = cur % w;
                int z = cur / w;
                if (x > 0 && height[cur - 1] > 0 && label[cur - 1] == 0) {
                    label[cur - 1] = next;
                    queue[tail++] = cur - 1;
                }
                if (x < w - 1 && height[cur + 1] > 0 && label[cur + 1] == 0) {
                    label[cur + 1] = next;
                    queue[tail++] = cur + 1;
                }
                if (z > 0 && height[cur - w] > 0 && label[cur - w] == 0) {
                    label[cur - w] = next;
                    queue[tail++] = cur - w;
                }
                if (z < d - 1 && height[cur + w] > 0 && label[cur + w] == 0) {
                    label[cur + w] = next;
                    queue[tail++] = cur + w;
                }
            }
        }
        return next;
    }
}
