package com.dwurdy.dawnmine.cave;

/** What is rock and what is cave in a planned stack: shared by ore and landmark placement. */
public final class Rock {
    public static final int MIN_Y = -58;
    public static final int MAX_Y = 120;
    private static final int[][] DIRS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private final CaveStack stack;

    public Rock(CaveStack stack) {
        this.stack = stack;
    }

    /** Hub box and its forecourt: never any ore or landmark (protected volumes). */
    public static boolean protectedColumn(int x, int z) {
        return (Math.abs(x) <= 26 && Math.abs(z) <= 26) || (x >= 16 && x <= 46 && Math.abs(z) <= 14);
    }

    /** A rock block that may be replaced: inside the shell, not protected, not open on any layer or tunnel. */
    public boolean solid(int x, int y, int z) {
        return y >= MIN_Y && y <= MAX_Y && !protectedColumn(x, z) && stack.sheet(0).contains(x, z)
                && !isOpenCell(x, y, z);
    }

    public boolean touchesOpen(int x, int y, int z) {
        for (int[] d : DIRS) {
            if (isOpenCell(x + d[0], y + d[1], z + d[2])) {
                return true;
            }
        }
        return false;
    }

    /** Whether a cell is cave air of any layer or tunnel. */
    public boolean isOpenCell(int x, int y, int z) {
        for (int layer = 0; layer < stack.layers(); layer++) {
            CaveSheet s = stack.sheet(layer);
            if (s.isOpen(x, z) && y > s.floorAt(x, z) && y <= s.floorAt(x, z) + s.heightAt(x, z)) {
                return true;
            }
        }
        int[] spans = stack.tubeSpans(x, z);
        if (spans != null) {
            for (int i = 0; i < spans.length; i += 2) {
                if (y >= spans[i] && y <= spans[i + 1]) {
                    return true;
                }
            }
        }
        return false;
    }
}
