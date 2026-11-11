package com.dwurdy.dawnmine.census;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * Sparse, generator-independent picture of a mine: only the cells the census
 * cares about are stored. Everything not listed as open or fluid is treated as
 * solid, so a scan only has to record passable cells, ore, and points of
 * interest. Pure data, no Minecraft classes, so analysis is unit-testable.
 */
public final class CensusSnapshot {
    /** A named vertical slice of the mine (inclusive Y bounds). */
    public record Band(String id, int minY, int maxY) { }

    private final CellSet open = new CellSet();
    private final CellSet fluid = new CellSet();
    private final Long2ObjectOpenHashMap<String> ore = new Long2ObjectOpenHashMap<>();
    private final LongArrayList mobs = new LongArrayList();
    private final LongArrayList loot = new LongArrayList();
    private final LongArrayList landmarks = new LongArrayList();
    private final java.util.List<Band> bands;
    private int chunksScanned;

    public CensusSnapshot(java.util.List<Band> bands) {
        this.bands = java.util.List.copyOf(bands);
    }

    // ---- cell packing (26 bits x, 26 bits z, 12 bits y) ----

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFFL);
    }

    public static int x(long p) { return (int) (p >> 38); }

    public static int y(long p) { return (int) (p & 0xFFFL) - 2048; }

    public static int z(long p) { return (int) (p << 26 >> 38); }

    // ---- recording ----

    public void addOpen(int x, int y, int z) { open.add(x, y, z); }

    public void addFluid(int x, int y, int z) { fluid.add(x, y, z); }

    public void addOre(int x, int y, int z, String oreId) { ore.put(pack(x, y, z), oreId); }

    public void addMob(int x, int y, int z) { mobs.add(pack(x, y, z)); }

    public void addLoot(int x, int y, int z) { loot.add(pack(x, y, z)); }

    public void addLandmark(int x, int y, int z) { landmarks.add(pack(x, y, z)); }

    public void addChunk() { chunksScanned++; }

    // ---- reading ----

    public boolean isOpen(long p) { return open.contains(x(p), y(p), z(p)); }

    public boolean isFluid(long p) { return fluid.contains(x(p), y(p), z(p)); }

    int openCount() { return open.size(); }

    int fluidCount() { return fluid.size(); }

    void forEachOpen(java.util.function.LongConsumer c) { open.forEach(c); }

    Long2ObjectOpenHashMap<String> ore() { return ore; }

    LongArrayList mobs() { return mobs; }

    LongArrayList loot() { return loot; }

    LongArrayList landmarks() { return landmarks; }

    public java.util.List<Band> bands() { return bands; }

    public int chunksScanned() { return chunksScanned; }

    /** Index of the band containing y, or -1. */
    public int bandIndex(int y) {
        for (int i = 0; i < bands.size(); i++) {
            Band b = bands.get(i);
            if (y >= b.minY() && y <= b.maxY()) {
                return i;
            }
        }
        return -1;
    }
}
