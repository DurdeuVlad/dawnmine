package com.dwurdy.dawnmine.generation;

import com.dwurdy.dawnmine.run.MineRunData;
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
            return "Dawnmine dimension is not loaded";
        }
        ChunkGenerator generator = mine.getChunkSource().getGenerator();
        int shellRadius;
        if (generator instanceof RunSeedNoiseLevelSource noise) {
            shellRadius = noise.shellRadiusChunks();
        } else if (generator instanceof BoundedFlatLevelSource bounded) {
            shellRadius = bounded.shellRadiusChunks();
        } else {
            return "mine must use dawnmine:run_noise or dawnmine:bounded_flat so its shell is generated before chunk access";
        }
        if (shellRadius != expectedRadiusChunks) {
            return "mineRegionRadiusChunks is " + expectedRadiusChunks + " but the dimension shell is "
                    + shellRadius + " chunks; make both values match before initializing the run";
        }
        return "";
    }

    public static String persistedBoundsError(MineRunData data, int expectedRadiusChunks) {
        if (data.ownedVolumes().stream()
                .anyMatch(volume -> !volume.dimension().location().toString().equals("dawnmine:mine"))) {
            return "saved run belongs to an older mine dimension; run '/dawnmine recover repair' to create a fresh v2 run";
        }
        if (data.boundMinChunkX() != -expectedRadiusChunks
                || data.boundMinChunkZ() != -expectedRadiusChunks
                || data.boundMaxChunkX() != expectedRadiusChunks - 1
                || data.boundMaxChunkZ() != expectedRadiusChunks - 1) {
            return "saved run bounds do not match the " + expectedRadiusChunks
                    + "-chunk bedrock shell; run '/dawnmine recover repair' after fixing config";
        }
        return "";
    }
}
