package com.rusticcraft.publicmines.generation;

import com.rusticcraft.publicmines.run.MineRunData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;

/** Coordinate rules shared by the worldgen adapter and shell tests. */
public final class MineShellGeometry {
    private MineShellGeometry() { }

    public static boolean inside(int chunkX, int chunkZ, int radiusChunks) {
        return chunkX >= -radiusChunks && chunkX < radiusChunks
                && chunkZ >= -radiusChunks && chunkZ < radiusChunks;
    }

    public static boolean isWestWall(int chunkX, int radiusChunks) {
        return chunkX == -radiusChunks;
    }

    public static boolean isEastWall(int chunkX, int radiusChunks) {
        return chunkX == radiusChunks - 1;
    }

    public static boolean isNorthWall(int chunkZ, int radiusChunks) {
        return chunkZ == -radiusChunks;
    }

    public static boolean isSouthWall(int chunkZ, int radiusChunks) {
        return chunkZ == radiusChunks - 1;
    }

    public static int minBlock(int radiusChunks) {
        return -radiusChunks * 16;
    }

    public static int maxBlock(int radiusChunks) {
        return radiusChunks * 16 - 1;
    }

    public static String configurationError(ServerLevel mine, int expectedRadiusChunks) {
        if (mine == null) {
            return "public mine dimension is not loaded";
        }
        ChunkGenerator generator = mine.getChunkSource().getGenerator();
        if (!(generator instanceof BoundedFlatLevelSource bounded)) {
            return "public_mine_v2 must use publicmines:bounded_flat so its shell is generated before chunk access";
        }
        if (bounded.shellRadiusChunks() != expectedRadiusChunks) {
            return "mineRegionRadiusChunks is " + expectedRadiusChunks + " but the dimension shell is "
                    + bounded.shellRadiusChunks() + " chunks; make both values match before initializing the run";
        }
        return "";
    }

    public static String persistedBoundsError(MineRunData data, int expectedRadiusChunks) {
        if (data.ownedVolumes().stream()
                .anyMatch(volume -> !volume.dimension().location().toString().equals("publicmines:public_mine_v2"))) {
            return "saved run belongs to an older mine dimension; run '/publicmine recover repair' to create a fresh v2 run";
        }
        if (data.boundMinChunkX() != -expectedRadiusChunks
                || data.boundMinChunkZ() != -expectedRadiusChunks
                || data.boundMaxChunkX() != expectedRadiusChunks - 1
                || data.boundMaxChunkZ() != expectedRadiusChunks - 1) {
            return "saved run bounds do not match the " + expectedRadiusChunks
                    + "-chunk bedrock shell; run '/publicmine recover repair' after fixing config";
        }
        return "";
    }
}
