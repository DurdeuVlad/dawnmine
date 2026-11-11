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
    /** The hub's east gateway: a 5-wide, 4-high tunnel through the wall (x 22..24) into the forecourt. */
    private static final int GATE_X0 = 22;
    private static final int GATE_X1 = 24;
    private static final int GATE_HALF_WIDTH = 2;
    private static final int GATE_HEIGHT = 4;
    /** The cave stack is designed and tested for the default 2048-block shell up to 96 chunks
     * (memory is about 28 bytes per column per layer). */
    private static final int MIN_SHELL_CHUNKS = 64;
    private static final int MAX_SHELL_CHUNKS = 96;
    /** Open regions smaller than this many columns are filled in instead of joined. */
    private static final int MIN_REGION = 60;
    private static final int CHANNEL_HEIGHT = 5;

    /** Number of stacked layers in the mine. */
    public static final int LAYERS = 3;

    /** Extent and vertical limits of one layer. Bounds are inclusive block coordinates.
     * Only the first layer ({@code hub}) has the hub box, gateway and forecourt. */
    public record Spec(int minX, int maxX, int minZ, int maxZ,
            int floorBase, int floorAmp, int maxHeight, int bandMinY, int bandMaxY,
            boolean hub, long salt) {

        /** The first layer of the 64-chunk shell. */
        public static Spec standard() {
            return layer(0, 64);
        }

        /** The first layer sized to a bedrock shell of the given chunk radius. */
        public static Spec forShell(int shellRadiusChunks) {
            return layer(0, shellRadiusChunks);
        }

        /** Layer {@code index} (0 = top, with the hub) sized to a shell of the given radius in chunks
         * (6 blocks of rock margin). Layers are separated by solid rock. */
        public static Spec layer(int index, int shellRadiusChunks) {
            if (shellRadiusChunks < MIN_SHELL_CHUNKS || shellRadiusChunks > MAX_SHELL_CHUNKS) {
                throw new IllegalArgumentException("cave layer supports shells of 64.." + MAX_SHELL_CHUNKS
                        + " chunks, not " + shellRadiusChunks);
            }
            int e = shellRadiusChunks * 16 - 6;
            return switch (index) {
                case 0 -> new Spec(-e, e, -e, e, 44, 12, 22, 24, 100, true, 0L);
                case 1 -> new Spec(-e, e, -e, e, -4, 8, 14, -26, 18, false, 0x1A7E2L);
                case 2 -> new Spec(-e, e, -e, e, -44, 5, 10, -58, -30, false, 0x2B8F3L);
                default -> throw new IllegalArgumentException("no layer " + index);
            };
        }

        /** A smaller square of the same layer, for tests. */
        public Spec withRadius(int radius) {
            return new Spec(-radius, radius, -radius, radius, floorBase, floorAmp, maxHeight,
                    bandMinY, bandMaxY, hub, salt);
        }
    }

    private CavePlanner() { }

    /** A fixed open spot a descent tunnel meets this layer at: floor and height are pinned along the
     * segment (x0,z0)-(x1,z1) out to {@code radius}, and the layer's floor blends back to its natural
     * shape over {@link #BLEND} blocks, so the tunnel mouth never opens onto a cliff. */
    public record Pin(int x0, int z0, int x1, int z1, int radius, int floor, int height, double blend) {
        public static Pin point(int x, int z, int radius, int floor, int height, double blend) {
            return new Pin(x, z, x, z, radius, floor, height, blend);
        }
    }

    /** The anchor-independent part of a layer: noise floors, open heights and pillars. Computing it
     * is the slow step (seconds), so it is done once and {@link #finish} reused for each pass. */
    public static final class Fields {
        final Spec spec;
        final int w;
        final int d;
        final float[] noiseFloor;
        final float[] rawHeight;
        final boolean[] pillar;

        private Fields(Spec spec, int w, int d) {
            this.spec = spec;
            this.w = w;
            this.d = d;
            this.noiseFloor = new float[w * d];
            this.rawHeight = new float[w * d];
            this.pillar = new boolean[w * d];
        }

        public Spec spec() { return spec; }

        public int index(int x, int z) {
            return (z - spec.minZ()) * w + (x - spec.minX());
        }
    }

    public static CaveSheet plan(Spec spec, long baseSeed) {
        return finish(fields(spec, baseSeed), java.util.List.of(), new java.util.BitSet());
    }

    public static Fields fields(Spec spec, long baseSeed) {
        long seed = baseSeed ^ spec.salt();
        int w = spec.maxX() - spec.minX() + 1;
        int d = spec.maxZ() - spec.minZ() + 1;
        Fields f = new Fields(spec, w, d);
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
                double fn = spec.floorBase() + spec.floorAmp()
                        * (0.7 * floorLow.fbm(x / 180.0, z / 180.0, 4, 2.0, 0.5)
                        + 0.3 * floorHigh.fbm(x / 60.0 + 100, z / 60.0, 3, 2.0, 0.5));
                f.noiseFloor[i] = (float) Math.max(floorMin, Math.min(floorMax, fn));

                double c = cavern.fbm((x + 500) / 110.0, (z - 300) / 110.0, 4, 2.0, 0.5);
                double hc = smooth(clamp01((c - 0.24) / 0.25));
                double raw = hc > 0 ? 4 + hc * (spec.maxHeight() - 4) : 0;
                raw = Math.max(raw, tunnel(tunA, tunAWidth, x, z, 0, 0));
                raw = Math.max(raw, tunnel(tunB, tunBWidth, x, z, 4000, -2500));
                f.rawHeight[i] = (float) raw;
                f.pillar[i] = raw > 0 && pillar.fbm(x / 14.0, z / 14.0, 2, 2.0, 0.5) > 0.55;
            }
        }
        return f;
    }

    /** Builds the walkable layer from precomputed fields, with extra pins and excluded columns
     * (bit index = {@link Fields#index}). Excluded columns stay solid rock and are never channelled. */
    public static CaveSheet finish(Fields f, java.util.List<Pin> pins, java.util.BitSet excluded) {
        Spec spec = f.spec;
        int w = f.w;
        int d = f.d;
        int[] floor = new int[w * d];
        int[] height = new int[w * d];
        for (int z = spec.minZ(); z <= spec.maxZ(); z++) {
            for (int x = spec.minX(); x <= spec.maxX(); x++) {
                int i = (z - spec.minZ()) * w + (x - spec.minX());
                // nearest pin: the forecourt on the hub layer, then any anchors
                double pd = spec.hub() ? pinDistance(x, z) : 1e9;
                int pinFloor = PIN_FLOOR;
                int pinHeight = PIN_HEIGHT;
                double pinBlend = BLEND;
                for (Pin pin : pins) {
                    double dd = pinDistance(pin, x, z);
                    if (dd < pd) {
                        pd = dd;
                        pinFloor = pin.floor();
                        pinHeight = pin.height();
                        pinBlend = pin.blend();
                    }
                }
                double s = pd <= 0 ? 0 : smooth(Math.min(1.0, pd / pinBlend));
                floor[i] = (int) Math.round(pinFloor + (f.noiseFloor[i] - pinFloor) * s);
                double raw = f.rawHeight[i];
                if (raw > 0 && pd > 4 && f.pillar[i]) {
                    raw = 0; // a rock pillar
                }
                height[i] = (int) Math.round(pinHeight + (raw - pinHeight) * s);
            }
        }

        chamfer(floor, w, d);

        for (Pin pin : pins) {
            int pi = (pin.z0() - spec.minZ()) * w + (pin.x0() - spec.minX());
            if (floor[pi] != pin.floor()) {
                throw new IllegalStateException("anchor floor moved from " + pin.floor() + " to "
                        + floor[pi] + " at " + pin.x0() + "," + pin.z0());
            }
        }
        for (int z = spec.minZ(); z <= spec.maxZ(); z++) {
            for (int x = spec.minX(); x <= spec.maxX(); x++) {
                int i = (z - spec.minZ()) * w + (x - spec.minX());
                if (spec.hub() && gateway(x, z)) {
                    if (floor[i] != PIN_FLOOR) {
                        throw new IllegalStateException("hub gateway floor was moved to " + floor[i]
                                + " at " + x + "," + z);
                    }
                    height[i] = GATE_HEIGHT;
                    continue;
                }
                int h = Math.min(height[i], spec.bandMaxY() - floor[i] - 1);
                if (h < CaveSheet.MIN_HEIGHT || forbidden(x, z, spec) || excluded.get(i)) {
                    h = 0;
                }
                height[i] = h;
            }
        }

        // Rock between forecourt and a floor that was lowered out of the pin would be a bug.
        int pinIndex = -1;
        if (spec.hub()) {
            pinIndex = (0 - spec.minZ()) * w + (PIN_X1 - spec.minX());
            if (floor[pinIndex] != PIN_FLOOR || height[pinIndex] <= 0) {
                throw new IllegalStateException("forecourt pin was not kept: floor=" + floor[pinIndex]
                        + " height=" + height[pinIndex]);
            }
        }
        for (Pin pin : pins) {
            int pi = (pin.z0() - spec.minZ()) * w + (pin.x0() - spec.minX());
            if (height[pi] <= 0) {
                throw new IllegalStateException("anchor at " + pin.x0() + "," + pin.z0() + " is not open");
            }
        }

        joinRegions(spec, floor, height, w, d, pinIndex, excluded);

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
        double dist = StrictMath.hypot(x - px, z);
        if (x >= PIN_X0 && dist <= PIN_RADIUS) {
            return 0;
        }
        return Math.max(1e-6, dist - PIN_RADIUS);
    }

    /** Distance outside a pin (0 inside it). */
    private static double pinDistance(Pin pin, int x, int z) {
        double sx = pin.x1() - pin.x0();
        double sz = pin.z1() - pin.z0();
        double len2 = sx * sx + sz * sz;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1.0, ((x - pin.x0()) * sx + (z - pin.z0()) * sz) / len2));
        double dist = StrictMath.hypot(x - (pin.x0() + sx * t), z - (pin.z0() + sz * t));
        return dist <= pin.radius() ? 0 : Math.max(1e-6, dist - pin.radius());
    }

    /** True for the hub's east gateway tunnel columns. */
    private static boolean gateway(int x, int z) {
        return x >= GATE_X0 && x <= GATE_X1 && Math.abs(z) <= GATE_HALF_WIDTH;
    }

    private static boolean forbidden(int x, int z, Spec spec) {
        if (spec.hub() && gateway(x, z)) {
            return false;
        }
        boolean inHub = spec.hub() && Math.abs(x) <= HUB_EXCLUDE && Math.abs(z) <= HUB_EXCLUDE;
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
    private static void joinRegions(Spec spec, int[] floor, int[] height, int w, int d, int pinIndex,
            java.util.BitSet excluded) {
        int n = w * d;
        int[] label = new int[n];
        int[] queue = new int[n];
        int labels = labelRegions(height, label, queue, w, d);
        int[] size = new int[labels + 1];
        for (int i = 0; i < n; i++) {
            if (label[i] > 0) {
                size[label[i]]++;
            }
        }
        int mainLabel = pinIndex >= 0 ? label[pinIndex] : largest(size);
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
                if (dist[ni] >= 0 || excluded.get(ni)
                        || forbidden(spec.minX() + nx, spec.minZ() + nz, spec)) {
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
                carveChannel(spec, floor, height, w, d, at, excluded);
            }
        }

        // Anything still not in the main region (blocked by the hub box or edge) is filled in.
        labels = labelRegions(height, label, queue, w, d);
        if (pinIndex >= 0) {
            mainLabel = label[pinIndex];
        } else {
            int[] sz = new int[labels + 1];
            for (int i = 0; i < n; i++) {
                if (label[i] > 0) {
                    sz[label[i]]++;
                }
            }
            mainLabel = largest(sz);
        }
        for (int i = 0; i < n; i++) {
            if (label[i] > 0 && label[i] != mainLabel) {
                height[i] = 0;
            }
        }
    }

    /** Label (1-based) with the most columns; ties go to the lowest label. */
    private static int largest(int[] size) {
        int best = 0;
        for (int l = 1; l < size.length; l++) {
            if (best == 0 || size[l] > size[best]) {
                best = l;
            }
        }
        return best;
    }

    /** Opens a 3-wide channel centred on a column, skipping forbidden columns. */
    private static void carveChannel(Spec spec, int[] floor, int[] height, int w, int d, int at,
            java.util.BitSet excluded) {
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
                        || forbidden(spec.minX() + nx, spec.minZ() + nz, spec)
                        || excluded.get(nz * w + nx)) {
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
