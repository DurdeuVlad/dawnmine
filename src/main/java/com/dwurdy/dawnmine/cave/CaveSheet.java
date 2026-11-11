package com.dwurdy.dawnmine.cave;

import com.dwurdy.dawnmine.census.CensusSnapshot;

/**
 * One walkable cave layer as a heightfield pair. For every (x, z) column it stores the
 * y of the standing floor block and the number of open cells above it (0 = solid rock).
 * The planner guarantees: floors of neighbouring columns differ by at most 1, every open
 * column has at least {@link #MIN_HEIGHT} open cells, and all open columns form one
 * 4-connected region that contains the hub forecourt. Pure data, no Minecraft classes.
 */
public final class CaveSheet {
    /** Fewest open cells above a floor: a player needs 2, stepping up needs 1 more, plus a spare. */
    public static final int MIN_HEIGHT = 4;

    private final int minX;
    private final int minZ;
    private final int sizeX;
    private final int sizeZ;
    private final int[] floor;
    private final byte[] height;

    CaveSheet(int minX, int minZ, int sizeX, int sizeZ, int[] floor, byte[] height) {
        this.minX = minX;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeZ = sizeZ;
        this.floor = floor;
        this.height = height;
    }

    public int minX() { return minX; }

    public int minZ() { return minZ; }

    public int maxX() { return minX + sizeX - 1; }

    public int maxZ() { return minZ + sizeZ - 1; }

    public int sizeX() { return sizeX; }

    public int sizeZ() { return sizeZ; }

    public boolean contains(int x, int z) {
        return x >= minX && x < minX + sizeX && z >= minZ && z < minZ + sizeZ;
    }

    public int index(int x, int z) {
        return (z - minZ) * sizeX + (x - minX);
    }

    /** y of the standing floor block (the player's feet are at floor + 1). */
    public int floorAt(int x, int z) {
        return floor[index(x, z)];
    }

    /** Open cells above the floor; 0 when the column is solid rock. */
    public int heightAt(int x, int z) {
        return height[index(x, z)];
    }

    public boolean isOpen(int x, int z) {
        return contains(x, z) && height[index(x, z)] > 0;
    }

    /** y of the lowest ceiling block above an open column. */
    public int ceilingAt(int x, int z) {
        return floor[index(x, z)] + height[index(x, z)] + 1;
    }

    public int openColumns() {
        int n = 0;
        for (byte h : height) {
            if (h > 0) {
                n++;
            }
        }
        return n;
    }

    /** Writes this sheet's open cells into a census snapshot (everything else stays solid). */
    public void addTo(CensusSnapshot snapshot) {
        for (int z = minZ; z < minZ + sizeZ; z++) {
            for (int x = minX; x < minX + sizeX; x++) {
                int i = index(x, z);
                for (int k = 1; k <= height[i]; k++) {
                    snapshot.addOpen(x, floor[i] + k, z);
                }
            }
        }
    }
}
