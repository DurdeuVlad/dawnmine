package com.dwurdy.dawnmine.generation;

import com.dwurdy.dawnmine.config.DawnmineConfig;
import java.util.Optional;

/** Pure, deterministic Chaos-only Netherrack cluster selection. */
public final class NetherrackPlacementPolicy {
    public record Cluster(int blockX, int baseY, int blockZ, int size) { }

    private NetherrackPlacementPolicy() { }

    public static Optional<Cluster> select(long seed, boolean enabled, int interval,
            int chunkX, int chunkZ, int chaosTopY, int chaosBottomY) {
        if (interval < DawnmineConfig.NETHERRACK_INTERVAL_MIN
                || interval > DawnmineConfig.NETHERRACK_INTERVAL_MAX) {
            throw new IllegalArgumentException("Netherrack interval must be within "
                    + DawnmineConfig.NETHERRACK_INTERVAL_MIN + ".." + DawnmineConfig.NETHERRACK_INTERVAL_MAX);
        }
        if (!enabled) {
            return Optional.empty();
        }
        int size = DawnmineConfig.NETHERRACK_CLUSTER_MAX_BLOCKS;
        int span = chaosTopY - chaosBottomY - size + 1;
        if (span < 1) {
            return Optional.empty();
        }
        long hash = chunkHash(seed, chunkX, chunkZ);
        if (Math.floorMod(hash, interval) != 0) {
            return Optional.empty();
        }
        int blockX = chunkX * 16 + Math.floorMod((int) (hash >>> 32), 16);
        int blockZ = chunkZ * 16 + Math.floorMod((int) (hash >>> 16), 16);
        int baseY = chaosBottomY + Math.floorMod((int) hash, span);
        return Optional.of(new Cluster(blockX, baseY, blockZ, size));
    }

    public static boolean contains(Cluster cluster, int x, int y, int z) {
        return cluster.blockX() == x && cluster.blockZ() == z
                && y >= cluster.baseY() && y < cluster.baseY() + cluster.size();
    }

    public static long chunkHash(long seed, int chunkX, int chunkZ) {
        return mix(seed ^ ((long) chunkX * 0x9E3779B97F4A7C15L)
                ^ ((long) chunkZ * 0xC2B2AE3D27D4EB4FL));
    }

    private static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
