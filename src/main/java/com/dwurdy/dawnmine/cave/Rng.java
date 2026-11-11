package com.dwurdy.dawnmine.cave;

/** splitmix64: tiny, fast and identical on every JVM. */
final class Rng {
    private long state;

    Rng(long seed) {
        this.state = seed;
    }

    long next() {
        long z = (state += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    int nextInt(int bound) {
        return (int) Long.remainderUnsigned(next() >>> 1, bound);
    }

    double nextDouble() {
        return (next() >>> 11) * 0x1.0p-53;
    }
}
