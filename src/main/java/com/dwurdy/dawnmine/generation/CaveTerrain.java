package com.dwurdy.dawnmine.generation;

import com.dwurdy.dawnmine.cave.CavePlanner;
import com.dwurdy.dawnmine.cave.CaveSheet;
import com.dwurdy.dawnmine.cave.CaveStack;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Applies the run's planned cave stack (three layers joined by descent tunnels) to terrain.
 * The stack is a pure function of the run seed and the shell size, so chunk generation,
 * restarts and the daily reset all see the same caves with nothing to record: the reset's
 * "restore to baseline" step simply regenerates them from the new run's seed.
 */
public final class CaveTerrain {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    /** Most recently used first-in-last-out cache; each value is shared by every waiter. */
    private static final java.util.LinkedHashMap<String, CompletableFuture<CaveStack>> CACHE =
            new java.util.LinkedHashMap<>(8, 0.75f, true);
    private static final int CACHE_SIZE = 4;

    private CaveTerrain() { }

    /** The cave stack for a run seed inside a shell of the given radius (cached; planning takes seconds). */
    public static CaveStack stack(long seed, int shellRadiusChunks) {
        String key = seed + ":" + shellRadiusChunks;
        CompletableFuture<CaveStack> future;
        boolean planner = false;
        synchronized (CACHE) {
            future = CACHE.get(key);
            if (future == null) {
                future = new CompletableFuture<>();
                CACHE.put(key, future);
                planner = true;
                while (CACHE.size() > CACHE_SIZE) {
                    CACHE.remove(CACHE.keySet().iterator().next()); // least recently used
                }
            }
        }
        if (planner) {
            try {
                future.complete(CaveStack.plan(shellRadiusChunks, seed));
            } catch (Throwable t) {
                synchronized (CACHE) {
                    CACHE.remove(key);
                }
                LOGGER.error("[dawnmine] cave stack planning failed for seed {} shell {}", seed,
                        shellRadiusChunks, t);
                future.completeExceptionally(t);
            }
        }
        return future.join();
    }

    /** Plans the stack on a background thread so the first chunk or reset step does not wait for it. */
    public static void prepareAsync(long seed, int shellRadiusChunks) {
        CompletableFuture.runAsync(() -> stack(seed, shellRadiusChunks))
                .exceptionally(t -> {
                    LOGGER.error("[dawnmine] background cave planning failed", t);
                    return null;
                });
    }

    /** Opens the planned cave cells inside one freshly filled chunk. */
    public static void carve(ChunkAccess chunk, CaveStack stack) {
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = minX + dx;
                int z = minZ + dz;
                for (int layer = 0; layer < stack.layers(); layer++) {
                    CaveSheet sheet = stack.sheet(layer);
                    if (!sheet.isOpen(x, z)) {
                        continue;
                    }
                    int floor = sheet.floorAt(x, z);
                    int top = floor + sheet.heightAt(x, z);
                    for (int y = floor + 1; y <= top; y++) {
                        chunk.setBlockState(pos.set(x, y, z), AIR, false);
                    }
                }
                int[] spans = stack.tubeSpans(x, z);
                if (spans != null) {
                    for (int i = 0; i < spans.length; i += 2) {
                        for (int y = spans[i]; y <= spans[i + 1]; y++) {
                            chunk.setBlockState(pos.set(x, y, z), AIR, false);
                        }
                    }
                }
            }
        }
    }

    /** A solid terrain column with the planned cave cells opened (reset baseline). */
    public static NoiseColumn carveColumn(NoiseColumn solid, int x, int z, int minY, int height,
                                          CaveStack stack) {
        boolean any = stack.tubeSpans(x, z) != null;
        for (int layer = 0; layer < stack.layers() && !any; layer++) {
            any = stack.sheet(layer).isOpen(x, z);
        }
        if (!any) {
            return solid;
        }
        BlockState[] states = new BlockState[height];
        for (int i = 0; i < height; i++) {
            states[i] = solid.getBlock(minY + i);
        }
        for (int layer = 0; layer < stack.layers(); layer++) {
            CaveSheet sheet = stack.sheet(layer);
            if (sheet.isOpen(x, z)) {
                open(states, minY, sheet.floorAt(x, z) + 1, sheet.floorAt(x, z) + sheet.heightAt(x, z));
            }
        }
        int[] spans = stack.tubeSpans(x, z);
        if (spans != null) {
            for (int i = 0; i < spans.length; i += 2) {
                open(states, minY, spans[i], spans[i + 1]);
            }
        }
        return new NoiseColumn(minY, states);
    }

    private static void open(BlockState[] states, int minY, int lo, int hi) {
        for (int y = Math.max(minY, lo); y <= Math.min(minY + states.length - 1, hi); y++) {
            states[y - minY] = AIR;
        }
    }
}
