package com.dwurdy.dawnmine.generation;

import com.mojang.serialization.MapCodec;
import com.dwurdy.dawnmine.Dawnmine;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Mod-owned worldgen type registry for the bounded dawnmine dimension. */
public final class DawnmineWorldgen {
    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, Dawnmine.MOD_ID);

    private DawnmineWorldgen() { }

    public static void register(net.neoforged.bus.api.IEventBus modEventBus) {
        CHUNK_GENERATORS.register("bounded_flat", () -> BoundedFlatLevelSource.CODEC);
        CHUNK_GENERATORS.register("run_noise", () -> RunSeedNoiseLevelSource.CODEC);
        CHUNK_GENERATORS.register(modEventBus);
    }
}
