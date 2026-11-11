package com.dwurdy.dawnmine.census;

import static com.dwurdy.dawnmine.census.CensusSnapshot.pack;
import static com.dwurdy.dawnmine.census.CensusSnapshot.x;
import static com.dwurdy.dawnmine.census.CensusSnapshot.y;
import static com.dwurdy.dawnmine.census.CensusSnapshot.z;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.function.LongConsumer;

/** Memory-lean set of cells: one 4096-bit mask (512 bytes) per 16x16x16 section. */
final class CellSet {
    private final Long2ObjectOpenHashMap<long[]> sections = new Long2ObjectOpenHashMap<>();
    private int size;

    private static long sectionKey(int x, int y, int z) {
        return pack(x >> 4, y >> 4, z >> 4);
    }

    private static int bit(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    /** True when the cell was newly added. */
    boolean add(int x, int y, int z) {
        long[] mask = sections.computeIfAbsent(sectionKey(x, y, z), k -> new long[64]);
        int b = bit(x, y, z);
        long before = mask[b >> 6];
        long after = before | (1L << (b & 63));
        if (after == before) {
            return false;
        }
        mask[b >> 6] = after;
        size++;
        return true;
    }

    boolean contains(int x, int y, int z) {
        long[] mask = sections.get(sectionKey(x, y, z));
        if (mask == null) {
            return false;
        }
        int b = bit(x, y, z);
        return (mask[b >> 6] & (1L << (b & 63))) != 0;
    }

    int size() {
        return size;
    }

    /** Visits every cell as a packed position (order is not significant). */
    void forEach(LongConsumer consumer) {
        for (var e : sections.long2ObjectEntrySet()) {
            long key = e.getLongKey();
            int bx = x(key) << 4;
            int by = y(key) << 4;
            int bz = z(key) << 4;
            long[] mask = e.getValue();
            for (int w = 0; w < 64; w++) {
                long bits = mask[w];
                while (bits != 0) {
                    int b = (w << 6) | Long.numberOfTrailingZeros(bits);
                    bits &= bits - 1;
                    consumer.accept(pack(bx | (b & 15), by | (b >> 8), bz | ((b >> 4) & 15)));
                }
            }
        }
    }
}
