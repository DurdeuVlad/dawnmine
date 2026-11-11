package com.dwurdy.dawnmine.generation;

import com.dwurdy.dawnmine.run.MineManager;
import com.dwurdy.dawnmine.run.MineRunData;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.StructureManager;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Vanilla noise terrain for the dawnmine dimension whose effective world
 * seed is the <em>current run's</em> seed: every daily run generates a
 * genuinely different cave world, while remaining deterministic inside
 * one run across restarts (chunk output depends only on the run seed).
 * Vanilla noise settings and cave biome sources supply real overworld
 * caves, aquifers and underground biomes; the bedrock shell is written
 * as a post-fill pass identical to the flat generator's baseline.
 */
public final class RunSeedNoiseLevelSource extends NoiseBasedChunkGenerator {
    public static final MapCodec<RunSeedNoiseLevelSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance
            .group(BiomeSource.CODEC.fieldOf("biome_source")
                            .forGetter(RunSeedNoiseLevelSource::getBiomeSource),
                    NoiseGeneratorSettings.CODEC.fieldOf("settings")
                            .forGetter(RunSeedNoiseLevelSource::generatorSettings),
                    Codec.intRange(16, 512).fieldOf("shell_radius_chunks")
                            .forGetter(RunSeedNoiseLevelSource::shellRadiusChunks))
            .apply(instance, RunSeedNoiseLevelSource::new));

    private final int shellRadiusChunks;
    private final ConcurrentHashMap<Long, RandomState> states = new ConcurrentHashMap<>();

    public RunSeedNoiseLevelSource(BiomeSource biomeSource,
                                   Holder<NoiseGeneratorSettings> settings, int shellRadiusChunks) {
        super(biomeSource, settings);
        if (shellRadiusChunks < 16 || shellRadiusChunks > 512) {
            throw new IllegalArgumentException("shell radius must be within 16..512 chunks");
        }
        this.shellRadiusChunks = shellRadiusChunks;
    }

    public int shellRadiusChunks() {
        return shellRadiusChunks;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
                                                        StructureManager structureManager, ChunkAccess chunk) {
        return super.fillFromNoise(blender, runState(randomState), structureManager, chunk)
                .thenApply(filled -> {
                    applyShell(filled);
                    carveCaves(filled);
                    return filled;
                });
    }

    @Override
    public void applyCarvers(net.minecraft.server.level.WorldGenRegion level, long seed,
                             RandomState random, BiomeManager biomeManager,
                             StructureManager structureManager, ChunkAccess chunk,
                             GenerationStep.Carving step) {
        super.applyCarvers(level, runSeed(seed), runState(random),
                biomeManager, structureManager, chunk, step);
    }

    /** The run's effective world seed: the active run's seed when a run is
     * loaded, otherwise the supplied fallback (the dimension's own seed). */
    private long runSeed(long fallback) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            MineRunData data = MineManager.get(server).data();
            if (data != null) {
                long seed = data.pendingSeed() != 0 ? data.pendingSeed() : data.seed();
                if (seed != 0) {
                    return seed;
                }
            }
            fallback = server.getWorldData().worldGenOptions().seed();
        }
        return fallback;
    }

    /** The terrain the noise generator produces for one column under the
     * current run's seed, before carving/decorations: the baseline the
     * daily reset restores inside owned volumes. Aquifer fluids and
     * carver tunnels are not reproduced (they are applied after fill);
     * restoring solid terrain there is intentional. */
    public net.minecraft.world.level.NoiseColumn baselineColumn(
            int x, int z, net.minecraft.world.level.LevelHeightAccessor level,
            RandomState fallback) {
        net.minecraft.world.level.NoiseColumn solid = getBaseColumn(x, z, level, runState(fallback));
        long seed = caveSeed();
        if (seed == NO_SEED || !MineShellGeometry.inside(x >> 4, z >> 4, shellRadiusChunks)) {
            return solid;
        }
        return CaveTerrain.carveColumn(solid, x, z, level.getMinBuildHeight(), level.getHeight(),
                CaveTerrain.sheet(seed, shellRadiusChunks));
    }

    private static final long NO_SEED = Long.MIN_VALUE;

    /** The run seed the caves are planned from, or {@link #NO_SEED} outside a running server. */
    private long caveSeed() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? NO_SEED : runSeed(server.getWorldData().worldGenOptions().seed());
    }

    /** Opens the run's planned cave cells in a chunk inside the shell. */
    private void carveCaves(ChunkAccess chunk) {
        long seed = caveSeed();
        ChunkPos pos = chunk.getPos();
        if (seed == NO_SEED || !MineShellGeometry.inside(pos.x, pos.z, shellRadiusChunks)) {
            return;
        }
        CaveTerrain.carve(chunk, CaveTerrain.sheet(seed, shellRadiusChunks));
    }

    /** The RandomState built under the current run's seed; used for
     * terrain fill, carving, baseline restoration, and structures. */
    public RandomState runState(RandomState fallback) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return fallback;
        }
        long seed = runSeed(server.getWorldData().worldGenOptions().seed());
        if (states.size() > 4) {
            states.clear();
        }
        return states.computeIfAbsent(seed, s -> RandomState.create(
                generatorSettings().value(),
                server.registryAccess().lookupOrThrow(Registries.NOISE), s));
    }

    /** Bedrock shell identical to the flat generator's baseline: side
     * walls at the outer edge columns of the shell ring plus a bedrock
     * lid at the world's top layer. */
    private void applyShell(ChunkAccess chunk) {
        ChunkPos pos = chunk.getPos();
        if (!MineShellGeometry.inside(pos.x, pos.z, shellRadiusChunks)) {
            return;
        }
        boolean west = MineShellGeometry.isWestWall(pos.x, shellRadiusChunks);
        boolean east = MineShellGeometry.isEastWall(pos.x, shellRadiusChunks);
        boolean north = MineShellGeometry.isNorthWall(pos.z, shellRadiusChunks);
        boolean south = MineShellGeometry.isSouthWall(pos.z, shellRadiusChunks);
        int minY = chunk.getMinBuildHeight();
        int topY = minY + chunk.getHeight() - 1;
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                boolean side = (west && x == 0) || (east && x == 15)
                        || (north && z == 0) || (south && z == 15);
                if (!side) {
                    continue;
                }
                for (int y = minY; y <= topY; y++) {
                    chunk.setBlockState(new net.minecraft.core.BlockPos(
                            pos.getMinBlockX() + x, y, pos.getMinBlockZ() + z),
                            bedrock, false);
                }
            }
        }
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                chunk.setBlockState(new net.minecraft.core.BlockPos(
                        pos.getMinBlockX() + x, topY, pos.getMinBlockZ() + z),
                        bedrock, false);
            }
        }
    }
}
