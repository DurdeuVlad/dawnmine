package com.dwurdy.dawnmine.generation;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.StructureManager;
import org.slf4j.Logger;

/** Flat dawnmine terrain with a generation-time bedrock enclosure. */
public final class BoundedFlatLevelSource extends FlatLevelSource {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final MapCodec<BoundedFlatLevelSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance
            .group(FlatLevelGeneratorSettings.CODEC.fieldOf("settings")
                            .forGetter(BoundedFlatLevelSource::settings),
                    Codec.intRange(16, 512).fieldOf("shell_radius_chunks")
                            .forGetter(BoundedFlatLevelSource::shellRadiusChunks))
            .apply(instance, BoundedFlatLevelSource::new));

    private final int shellRadiusChunks;

    public BoundedFlatLevelSource(FlatLevelGeneratorSettings settings, int shellRadiusChunks) {
        super(settings);
        if (shellRadiusChunks < 16 || shellRadiusChunks > 512) {
            throw new IllegalArgumentException("shell radius must be within 16..512 chunks");
        }
        this.shellRadiusChunks = shellRadiusChunks;
    }

    public int shellRadiusChunks() {
        return shellRadiusChunks;
    }

    @Override
    protected MapCodec<? extends BoundedFlatLevelSource> codec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
                                                         StructureManager structureManager, ChunkAccess chunk) {
        return super.fillFromNoise(blender, randomState, structureManager, chunk).thenApply(generated -> {
            applyShell(generated);
            return generated;
        });
    }

    private void applyShell(ChunkAccess chunk) {
        long started = System.nanoTime();
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        if (!MineShellGeometry.inside(chunkX, chunkZ, shellRadiusChunks)) {
            return;
        }

        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = chunk.getMinBuildHeight();
        int topY = chunk.getMinBuildHeight() + chunk.getHeight() - 1;
        boolean west = MineShellGeometry.isWestWall(chunkX, shellRadiusChunks);
        boolean east = MineShellGeometry.isEastWall(chunkX, shellRadiusChunks);
        boolean north = MineShellGeometry.isNorthWall(chunkZ, shellRadiusChunks);
        boolean south = MineShellGeometry.isSouthWall(chunkZ, shellRadiusChunks);
        int changedBlocks = 0;

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                boolean side = (west && localX == 0) || (east && localX == 15)
                        || (north && localZ == 0) || (south && localZ == 15);
                if (side) {
                    for (int y = minY; y <= topY; y++) {
                        if (setBedrock(chunk, pos.set(localX, y, localZ), localX, localZ, y,
                                oceanFloor, worldSurface)) {
                            changedBlocks++;
                        }
                    }
                } else {
                    if (setBedrock(chunk, pos.set(localX, topY, localZ), localX, localZ, topY,
                            oceanFloor, worldSurface)) {
                        changedBlocks++;
                    }
                }
            }
        }
        if (west || east || north || south) {
            LOGGER.info("[dawnmine] Generated bedrock shell chunk [{}, {}]: {} changed blocks in {} ms",
                    chunkX, chunkZ, changedBlocks,
                    String.format(java.util.Locale.ROOT, "%.2f", (System.nanoTime() - started) / 1_000_000.0));
        }
    }

    private static boolean setBedrock(ChunkAccess chunk, BlockPos pos, int localX, int localZ, int y,
                                      Heightmap oceanFloor, Heightmap worldSurface) {
        BlockState existing = chunk.getBlockState(pos);
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        if (existing.equals(bedrock)) {
            return false;
        }
        chunk.setBlockState(pos, bedrock, false);
        oceanFloor.update(localX, y, localZ, bedrock);
        worldSurface.update(localX, y, localZ, bedrock);
        return true;
    }
}
