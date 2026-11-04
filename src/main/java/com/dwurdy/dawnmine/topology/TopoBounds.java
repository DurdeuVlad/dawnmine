package com.dwurdy.dawnmine.topology;

/**
 * Inclusive axis-aligned block volume the topology must stay inside. For the
 * shared mine this is derived from the M1 {@code active_region} owned volume
 * (chunk bounds expanded to block coordinates, full dimension height).
 */
public record TopoBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public boolean isEmpty() {
        return maxX < minX || maxY < minY || maxZ < minZ;
    }

    public int sizeX() { return maxX - minX + 1; }
    public int sizeY() { return maxY - minY + 1; }
    public int sizeZ() { return maxZ - minZ + 1; }

    public boolean contains(TopoPos p) {
        return p.x() >= minX && p.x() <= maxX
                && p.y() >= minY && p.y() <= maxY
                && p.z() >= minZ && p.z() <= maxZ;
    }

    /** True when the box of radius {@code half} around {@code center} fits. */
    public boolean containsBox(TopoPos center, int half) {
        return contains(new TopoPos(center.x() - half, center.y() - half, center.z() - half))
                && contains(new TopoPos(center.x() + half, center.y() + half, center.z() + half));
    }

    /** Shrinks every face inward by {@code margin}; may become empty. */
    public TopoBounds inset(int margin) {
        return new TopoBounds(minX + margin, minY + margin, minZ + margin,
                maxX - margin, maxY - margin, maxZ - margin);
    }

    public int clampX(int x) { return Math.max(minX, Math.min(maxX, x)); }
    public int clampY(int y) { return Math.max(minY, Math.min(maxY, y)); }
    public int clampZ(int z) { return Math.max(minZ, Math.min(maxZ, z)); }

    @Override
    public String toString() {
        return "[" + minX + "," + minY + "," + minZ + ";" + maxX + "," + maxY + "," + maxZ + "]";
    }
}
