package com.dwurdy.dawnmine.cave;

import java.util.SplittableRandom;

/** Small deterministic 2D gradient noise with fractal sums. Pure, no Minecraft classes. */
public final class Noise2D {
    private static final double INV_SQRT2 = 0.7071067811865476;
    private static final double[][] GRAD = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {INV_SQRT2, INV_SQRT2}, {-INV_SQRT2, INV_SQRT2},
            {INV_SQRT2, -INV_SQRT2}, {-INV_SQRT2, -INV_SQRT2}};

    private final int[] perm = new int[512];

    public Noise2D(long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) {
            p[i] = i;
        }
        for (int i = 255; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        for (int i = 0; i < 512; i++) {
            perm[i] = p[i & 255];
        }
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private double dot(int hash, double x, double y) {
        double[] g = GRAD[hash & 7];
        return g[0] * x + g[1] * y;
    }

    /** Gradient noise, roughly in [-1, 1], zero at integer lattice points. */
    public double noise(double x, double y) {
        int xi = (int) Math.floor(x);
        int yi = (int) Math.floor(y);
        double xf = x - xi;
        double yf = y - yi;
        int xm = xi & 255;
        int ym = yi & 255;
        double u = fade(xf);
        double v = fade(yf);
        int aa = perm[perm[xm] + ym];
        int ab = perm[perm[xm] + ym + 1];
        int ba = perm[perm[xm + 1] + ym];
        int bb = perm[perm[xm + 1] + ym + 1];
        double x1 = lerp(dot(aa, xf, yf), dot(ba, xf - 1, yf), u);
        double x2 = lerp(dot(ab, xf, yf - 1), dot(bb, xf - 1, yf - 1), u);
        return lerp(x1, x2, v) * 1.4142135623730951; // scale the +-0.707 range to about +-1
    }

    /** Fractal sum normalised to roughly [-1, 1]. */
    public double fbm(double x, double y, int octaves, double lacunarity, double gain) {
        double sum = 0;
        double amp = 1;
        double norm = 0;
        double fx = x;
        double fy = y;
        for (int i = 0; i < octaves; i++) {
            sum += amp * noise(fx, fy);
            norm += amp;
            amp *= gain;
            fx *= lacunarity;
            fy *= lacunarity;
            // decorrelate octaves
            fx += 17.31;
            fy += 9.77;
        }
        return sum / norm;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
