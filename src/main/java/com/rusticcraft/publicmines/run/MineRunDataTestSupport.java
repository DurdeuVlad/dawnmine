package com.rusticcraft.publicmines.run;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * Test-only hooks shared by server-side GameTests that live outside the
 * {@code run} package. Production code must not call these; the GameTests
 * snapshot the shared record first and restore it afterwards.
 */
public final class MineRunDataTestSupport {
    private MineRunDataTestSupport() { }

    public static CompoundTag snapshot(MineRunData data, HolderLookup.Provider registries) {
        return data.snapshot(registries);
    }

    public static void restore(MineRunData data, CompoundTag snapshot, HolderLookup.Provider registries) {
        data.restoreSnapshot(snapshot, registries);
    }

    /** Fabricates a minimal READY run with a recorded surface exit and its fixed safe zone. */
    public static boolean makeReadyWithSurfaceExit(MineRunData data, BlockPos surfaceExit) {
        data.beginNewRun("test-run-" + UUID.randomUUID(), 1, 0x5D15BA1L, "2099-01-01");
        data.setSurfaceExit(surfaceExit);
        if (!data.registerSurfaceExitSafeZone()) {
            return false;
        }
        data.markStatus(RunStatus.READY);
        return true;
    }

    /** Adds a full-height mine owned region so test stations can pass the ownership gate. */
    public static void addMineOwnedRegion(MineRunData data, int radiusChunks,
                                          int minY, int maxY) {
        data.setBounds(-radiusChunks, -radiusChunks, radiusChunks - 1, radiusChunks - 1);
        data.addOwnedVolume(new OwnedVolume("test_active_region", MineManager.MINE_DIMENSION,
                new BlockPos(-radiusChunks * 16, minY, -radiusChunks * 16),
                new BlockPos(radiusChunks * 16 - 1, maxY, radiusChunks * 16 - 1)));
    }
}
