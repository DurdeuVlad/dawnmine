package com.dwurdy.dawnmine.topology;

/**
 * Immutable block-coordinate position inside the topology plan. Kept free of
 * Minecraft types so the planner and its tests stay a pure function of
 * (seed, profile, bounds); world code converts to {@code BlockPos} at the
 * block-placement boundary (M2b).
 */
public record TopoPos(int x, int y, int z) {

    public TopoPos offset(int dx, int dy, int dz) {
        return new TopoPos(x + dx, y + dy, z + dz);
    }

    public double distanceTo(TopoPos other) {
        return Math.sqrt(distanceSqTo(other));
    }

    public double distanceSqTo(TopoPos other) {
        long dx = (long) x - other.x;
        long dy = (long) y - other.y;
        long dz = (long) z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public String toString() {
        return x + "," + y + "," + z;
    }
}
