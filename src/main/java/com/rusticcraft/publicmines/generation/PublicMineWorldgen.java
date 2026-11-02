package com.rusticcraft.publicmines.generation;

import com.mojang.serialization.MapCodec;
import com.rusticcraft.publicmines.PublicMines;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Mod-owned worldgen type registry for the bounded public-mine dimension. */
public final class PublicMineWorldgen {
    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, PublicMines.MOD_ID);

    private PublicMineWorldgen() { }

    public static void register(net.neoforged.bus.api.IEventBus modEventBus) {
        CHUNK_GENERATORS.register("bounded_flat", () -> BoundedFlatLevelSource.CODEC);
        CHUNK_GENERATORS.register(modEventBus);
    }
}
