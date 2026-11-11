package com.dwurdy.dawnmine.generation;

import com.dwurdy.dawnmine.cave.CavePlanner;
import com.dwurdy.dawnmine.cave.CaveSheet;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Applies the run's planned cave layer to terrain. The layer is a pure function of the run
 * seed and the shell size, so chunk generation, restarts and the daily reset all see the
 * same caves with nothing to record: the reset's "restore to baseline" step simply
 * regenerates them from the new run's seed.
 */
public final class CaveTerrain {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final ConcurrentHashMap<String, CaveSheet> SHEETS = new ConcurrentHashMap<>();

    private CaveTerrain() { }

    /** The cave layer for a run seed inside a shell of the given radius (cached; planning takes seconds). */
    public static CaveSheet sheet(long seed, int shellRadiusChunks) {
        if (SHEETS.size() > 3) {
            SHEETS.clear();
        }
        return SHEETS.computeIfAbsent(seed + ":" + shellRadiusChunks,
                k -> CavePlanner.plan(CavePlanner.Spec.forShell(shellRadiusChunks), seed));
    }

    /** Opens the planned cave cells inside one freshly filled chunk. */
    public static void carve(ChunkAccess chunk, CaveSheet sheet) {
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = minX + dx;
                int z = minZ + dz;
                if (!sheet.isOpen(x, z)) {
                    continue;
                }
                int floor = sheet.floorAt(x, z);
                int top = floor + sheet.heightAt(x, z);
                for (int y = floor + 1; y <= top; y++) {
                    chunk.setBlockState(pos.set(x, y, z), AIR, false);
                }
            }
        }
    }

    /** A solid terrain column with the planned cave cells opened (reset baseline). */
    public static NoiseColumn carveColumn(NoiseColumn solid, int x, int z, int minY, int height,
                                          CaveSheet sheet) {
        if (!sheet.isOpen(x, z)) {
            return solid;
        }
        BlockState[] states = new BlockState[height];
        for (int i = 0; i < height; i++) {
            states[i] = solid.getBlock(minY + i);
        }
        int floor = sheet.floorAt(x, z);
        int top = floor + sheet.heightAt(x, z);
        for (int y = Math.max(minY, floor + 1); y <= Math.min(minY + height - 1, top); y++) {
            states[y - minY] = AIR;
        }
        return new NoiseColumn(minY, states);
    }
}
