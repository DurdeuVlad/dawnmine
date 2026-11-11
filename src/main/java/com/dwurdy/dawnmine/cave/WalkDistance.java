package com.dwurdy.dawnmine.cave;

/**
 * Walking distance over one cave sheet, in tenths of a block (orthogonal step 10, diagonal 14).
 * A diagonal step needs both orthogonal neighbours open, matching the census walker's corner rule.
 * Because floors of neighbouring open columns differ by at most 1 and headroom is at least 4,
 * every open-to-open step on a sheet is walkable.
 */
public final class WalkDistance {
    private WalkDistance() { }

    /** Distance from the nearest source column to every column; -1 where unreachable or solid. */
    public static int[] from(CaveSheet s, int[][] sources) {
        int w = s.sizeX();
        int d = s.sizeZ();
        int n = w * d;
        int[] dist = new int[n];
        java.util.Arrays.fill(dist, -1);
        long[] heap = new long[Math.max(16, n / 4)];
        int size = 0;
        for (int[] src : sources) {
            if (!s.isOpen(src[0], src[1])) {
                continue;
            }
            int i = s.index(src[0], src[1]);
            if (dist[i] != 0) {
                dist[i] = 0;
                if (size == heap.length) {
                    heap = java.util.Arrays.copyOf(heap, size * 2);
                }
                heap[size++] = (long) i; // dist 0 in the high bits
                siftUp(heap, size - 1);
            }
        }
        int[] best = new int[n];
        java.util.Arrays.fill(best, Integer.MAX_VALUE);
        for (int[] src : sources) {
            if (s.isOpen(src[0], src[1])) {
                best[s.index(src[0], src[1])] = 0;
            }
        }
        while (size > 0) {
            long top = heap[0];
            heap[0] = heap[--size];
            if (size > 0) {
                siftDown(heap, size, 0);
            }
            int cur = (int) (top & 0xFFFFFFFFL);
            int cd = (int) (top >>> 32);
            if (cd > best[cur]) {
                continue;
            }
            dist[cur] = cd;
            int x = s.minX() + cur % w;
            int z = s.minZ() + cur / w;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int nx = x + dx;
                    int nz = z + dz;
                    if (!s.isOpen(nx, nz)) {
                        continue;
                    }
                    int step = 10;
                    if (dx != 0 && dz != 0) {
                        if (!s.isOpen(x + dx, z) || !s.isOpen(x, z + dz)) {
                            continue;
                        }
                        step = 14;
                    }
                    int ni = s.index(nx, nz);
                    int nd = cd + step;
                    if (nd < best[ni]) {
                        best[ni] = nd;
                        if (size == heap.length) {
                            heap = java.util.Arrays.copyOf(heap, size * 2);
                        }
                        heap[size++] = ((long) nd << 32) | ni;
                        siftUp(heap, size - 1);
                    }
                }
            }
        }
        return dist;
    }

    private static void siftUp(long[] h, int i) {
        long v = h[i];
        while (i > 0) {
            int p = (i - 1) >> 1;
            if (h[p] <= v) {
                break;
            }
            h[i] = h[p];
            i = p;
        }
        h[i] = v;
    }

    private static void siftDown(long[] h, int size, int i) {
        long v = h[i];
        while (true) {
            int c = 2 * i + 1;
            if (c >= size) {
                break;
            }
            if (c + 1 < size && h[c + 1] < h[c]) {
                c++;
            }
            if (h[c] >= v) {
                break;
            }
            h[i] = h[c];
            i = c;
        }
        h[i] = v;
    }
}
