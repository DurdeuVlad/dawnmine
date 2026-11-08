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

    /** Golden-ratio mix folded into the run seed per topology draw, so a
     * re-drawn plan is an independent deterministic sample of the same
     * space rather than a small perturbation of the failed one. */
    private static final long DRAW_SEED_MIX = 0x9E3779B97F4A7C15L;

    /** The seed a given topology draw plans with; exposed for tests that
     * replay the draw sequence without a run record. */
    public static long drawSeed(long seed, int draw) {
        return seed ^ draw * DRAW_SEED_MIX;
    }

    /** Plans the run's topology from its persisted seed + recorded draw
     * and recorded bounds. Replays the receipted draw index, so a saved
     * run always reproduces the topology it was planned with. */
    public static TopologyPlan plan(MineRunData data, ServerLevel mine) {
        return plan(data, mine, data.topologySeedOffset());
    }

    /** Plans topology draw {@code draw}: {@code draw = 0} is the run's
     * base seed, larger values mix in {@link #DRAW_SEED_MIX} so the draw
     * sequence is a deterministic walk of independent candidates. The
     * first draw that resolves is receipted via
     * {@link MineRunData#setTopology}; later replans read it back.
     *
     * @throws TopologyException when the bounds are unusable or no valid
     *         profile is loaded. Callers surface this as a clean failure.
     */
    public static TopologyPlan plan(MineRunData data, ServerLevel mine, int draw) {
        if (data.boundMaxChunkX() < data.boundMinChunkX()
                || data.boundMaxChunkZ() < data.boundMinChunkZ()) {
            throw new TopologyException("run record has no bounds to plan within");
        }
        return TopologyPlanner.plan(data.seed() ^ draw * DRAW_SEED_MIX,
                TopologyProfiles.active(), boundsFor(data, mine));
    }

    /** Validates a plan against the recorded bounds and the active profile. */
    public static TopologyReport validate(TopologyPlan plan) {
        return TopologyValidator.validate(plan, TopologyProfiles.active());
    }
}
