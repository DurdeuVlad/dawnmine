package com.dwurdy.dawnmine.generation;

import com.dwurdy.dawnmine.cave.CavePlanner;
import com.dwurdy.dawnmine.cave.CaveSheet;
import com.dwurdy.dawnmine.cave.CaveStack;
import com.dwurdy.dawnmine.cave.OreField;
import java.util.ArrayList;
import java.util.List;
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
    /** Least-recently-used cache; each value (including a failure) is shared by every waiter. */
    private static final java.util.LinkedHashMap<String, CompletableFuture<CaveStack>> CACHE =
            new java.util.LinkedHashMap<>(8, 0.75f, true);
    private static final int CACHE_SIZE = 4;

    private CaveTerrain() { }

    /** The ore field for a stack with the block of each ore resolved once. */
    private record Ores(OreField field, BlockState[] states, String fingerprint) { }

    private static final java.util.IdentityHashMap<CaveStack, Ores> ORES = new java.util.IdentityHashMap<>();

    /** Ore veins for a stack, built from the active catalogue (layer i uses ore tier i). */
    private static Ores ores(CaveStack stack, long seed) {
        OreCatalog.OreCatalogSnapshot catalog = OreCatalog.active();
        synchronized (ORES) {
            Ores held = ORES.get(stack);
            if (held != null) {
                return held; // pinned for the stack lifetime: a catalogue reload never changes a run's ore
            }
            List<List<OreField.Ore>> tables = new ArrayList<>();
            for (OreCatalog.Tier tier : OreCatalog.Tier.values()) {
                List<OreField.Ore> table = new ArrayList<>();
                for (OreCatalog.Entry e : catalog.resourceEntries(tier)) {
                    table.add(new OreField.Ore(e.blockId().toString(), e.weight(), e.maxVeinSize()));
                }
                tables.add(table);
            }
            OreField field = new OreField(stack, seed, tables);
            int n = field.oreCount();
            BlockState[] states = new BlockState[n];
            for (int i = 0; i < n; i++) {
                states[i] = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .get(net.minecraft.resources.ResourceLocation.parse(field.ore(i).id())).defaultBlockState();
            }
            Ores made = new Ores(field, states, catalog.fingerprint());
            if (!catalog.entries().isEmpty()) { // never pin the empty catalogue seen before data loads
                if (ORES.size() >= 2) {
                    ORES.clear(); // each held field pins its stack: keep the current and previous run only
                }
                ORES.put(stack, made);
            }
            return made;
        }
    }

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
                // keep the failed future cached: waiters fail at once instead of each re-planning
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
    public static void carve(ChunkAccess chunk, CaveStack stack, long seed) {
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
        Ores ores = ores(stack, seed);
        for (var column : ores.field().forChunk(chunk.getPos().x, chunk.getPos().z).byColumn().entrySet()) {
            int x = (int) (column.getKey() >> 32);
            int z = (int) (long) column.getKey();
            int[] ys = column.getValue();
            for (int i = 0; i < ys.length; i += 2) {
                chunk.setBlockState(pos.set(x, ys[i], z), ores.states()[ys[i + 1]], false);
            }
        }
    }

    /** A solid terrain column with the planned cave cells opened (reset baseline). */
    public static NoiseColumn carveColumn(NoiseColumn solid, int x, int z, int minY, int height,
                                          CaveStack stack, long seed) {
        Ores ores = ores(stack, seed);
        int[] oreYs = ores.field().forChunk(x >> 4, z >> 4).byColumn().get(CaveStack.key(x, z));
        boolean any = stack.tubeSpans(x, z) != null || oreYs != null;
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
        if (oreYs != null) {
            for (int i = 0; i < oreYs.length; i += 2) {
                if (oreYs[i] >= minY && oreYs[i] < minY + height) {
                    states[oreYs[i] - minY] = ores.states()[oreYs[i + 1]];
                }
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
