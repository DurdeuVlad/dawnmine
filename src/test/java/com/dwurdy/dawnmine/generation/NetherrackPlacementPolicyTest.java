package com.dwurdy.dawnmine.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.dawnmine.config.DawnmineConfig;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NetherrackPlacementPolicyTest {
    @Test
    void defaultsDisabledAndEnabledClustersAreSeededCappedAndDeepOnly() {
        assertTrue(NetherrackPlacementPolicy.select(42L, false, 256,
                0, 0, -16, -56).isEmpty());

        Set<String> selectedColumns = new HashSet<>();
        for (int x = -300; x < 300; x++) {
            for (int z = -300; z < 300; z++) {
                var selected = NetherrackPlacementPolicy.select(42L, true, 256,
                        x, z, -16, -56);
                if (selected.isEmpty()) {
                    continue;
                }
                var cluster = selected.orElseThrow();
                assertTrue(cluster.size() <= DawnmineConfig.NETHERRACK_CLUSTER_MAX_BLOCKS);
                assertTrue(cluster.blockX() >= x * 16 && cluster.blockX() < x * 16 + 16);
                assertTrue(cluster.blockZ() >= z * 16 && cluster.blockZ() < z * 16 + 16);
                assertTrue(cluster.baseY() >= -56 && cluster.baseY() + cluster.size() - 1 <= -17);
                assertTrue(selectedColumns.add(x + ":" + z), "more than one cluster in a chunk column");
                assertTrue(NetherrackPlacementPolicy.contains(cluster,
                        cluster.blockX(), cluster.baseY(), cluster.blockZ()));
                assertFalse(NetherrackPlacementPolicy.contains(cluster,
                        cluster.blockX() + 1, cluster.baseY(), cluster.blockZ()));
            }
        }
        assertTrue(selectedColumns.size() > 0 && selectedColumns.size() < 3000);
    }

    @Test
    void intervalBoundsAreHardAndSelectionIsDeterministic() {
        var first = NetherrackPlacementPolicy.select(7L, true,
                DawnmineConfig.NETHERRACK_INTERVAL_MIN, 1, 2, -16, -56);
        var replay = NetherrackPlacementPolicy.select(7L, true,
                DawnmineConfig.NETHERRACK_INTERVAL_MIN, 1, 2, -16, -56);
        assertEquals(first, replay);

        int commonIntervalAttempts = 0;
        int rareIntervalAttempts = 0;
        for (int chunkX = 0; chunkX < 131_072; chunkX++) {
            if (NetherrackPlacementPolicy.select(7L, true,
                    DawnmineConfig.NETHERRACK_INTERVAL_MIN, chunkX, 0, -16, -56).isPresent()) {
                commonIntervalAttempts++;
            }
            if (NetherrackPlacementPolicy.select(7L, true,
                    DawnmineConfig.NETHERRACK_INTERVAL_MAX, chunkX, 0, -16, -56).isPresent()) {
                rareIntervalAttempts++;
            }
        }
        assertTrue(commonIntervalAttempts > rareIntervalAttempts,
                "raising the interval must make placements rarer");
        assertThrows(IllegalArgumentException.class, () -> NetherrackPlacementPolicy.select(
                7L, true, DawnmineConfig.NETHERRACK_INTERVAL_MIN - 1, 0, 0, -16, -56));
        assertThrows(IllegalArgumentException.class, () -> NetherrackPlacementPolicy.select(
                7L, true, DawnmineConfig.NETHERRACK_INTERVAL_MAX + 1, 0, 0, -16, -56));
    }
}
