package com.dwurdy.dawnmine.topology;

import com.dwurdy.dawnmine.run.MineRunData;
import net.minecraft.server.level.ServerLevel;

/**
 * Boundary between the pure topology module and the M1 run state. Converts
 * the recorded mine bounds and dimension height into {@link TopoBounds},
 * plans and validates against the active profile, and returns results the
 * lifecycle can persist or report. No block is ever placed here.
 */
public final class MineTopology {

    private MineTopology() {}

    /** The mine-owned planning volume: the run's recorded chunk bounds
     * expanded to block coordinates across the full dimension height. */
    public static TopoBounds boundsFor(MineRunData data, ServerLevel mine) {
        int minX = Math.min(data.boundMinChunkX(), data.boundMaxChunkX()) * 16;
        int maxX = Math.max(data.boundMinChunkX(), data.boundMaxChunkX()) * 16 + 15;
        int minZ = Math.min(data.boundMinChunkZ(), data.boundMaxChunkZ()) * 16;
        int maxZ = Math.max(data.boundMinChunkZ(), data.boundMaxChunkZ()) * 16 + 15;
        int minY = mine.getMinBuildHeight();
        int maxY = minY + mine.getHeight() - 1;
        return new TopoBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Plans the run's topology from its persisted seed and recorded bounds.
     *
     * @throws TopologyException when the bounds are unusable or no valid
     *         profile is loaded. Callers surface this as a clean failure.
     */
    public static TopologyPlan plan(MineRunData data, ServerLevel mine) {
        if (data.boundMaxChunkX() < data.boundMinChunkX()
                || data.boundMaxChunkZ() < data.boundMinChunkZ()) {
            throw new TopologyException("run record has no bounds to plan within");
        }
        return TopologyPlanner.plan(data.seed(), TopologyProfiles.active(),
                boundsFor(data, mine));
    }

    /** Validates a plan against the recorded bounds and the active profile. */
    public static TopologyReport validate(TopologyPlan plan) {
        return TopologyValidator.validate(plan, TopologyProfiles.active());
    }
}
