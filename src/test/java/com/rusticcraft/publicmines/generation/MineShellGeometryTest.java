package com.rusticcraft.publicmines.generation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rusticcraft.publicmines.run.MineRunData;
import com.rusticcraft.publicmines.run.OwnedVolume;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class MineShellGeometryTest {
    @Test
    void shellFacesAndCornersAreExactlyTheOuterChunkRing() {
        int radius = 64;
        assertTrue(MineShellGeometry.inside(-radius, 0, radius));
        assertTrue(MineShellGeometry.inside(radius - 1, 0, radius));
        assertTrue(MineShellGeometry.inside(0, -radius, radius));
        assertTrue(MineShellGeometry.inside(0, radius - 1, radius));
        assertTrue(MineShellGeometry.inside(-radius, -radius, radius));
        assertTrue(MineShellGeometry.inside(radius - 1, radius - 1, radius));
        assertFalse(MineShellGeometry.inside(-radius - 1, 0, radius));
        assertFalse(MineShellGeometry.inside(radius, 0, radius));
        assertFalse(MineShellGeometry.inside(0, radius, radius));
        assertTrue(MineShellGeometry.isWestWall(-radius, radius));
        assertTrue(MineShellGeometry.isEastWall(radius - 1, radius));
        assertTrue(MineShellGeometry.isNorthWall(-radius, radius));
        assertTrue(MineShellGeometry.isSouthWall(radius - 1, radius));
        assertTrue(MineShellGeometry.isWestWall(-radius, radius)
                && MineShellGeometry.isNorthWall(-radius, radius));
        assertTrue(MineShellGeometry.isEastWall(radius - 1, radius)
                && MineShellGeometry.isSouthWall(radius - 1, radius));
    }

    @Test
    void boundaryBlockCoordinatesMatchThePersistedChunkBounds() {
        org.junit.jupiter.api.Assertions.assertEquals(-1024, MineShellGeometry.minBlock(64));
        org.junit.jupiter.api.Assertions.assertEquals(1023, MineShellGeometry.maxBlock(64));
    }

    @Test
    void savedRunFromUnversionedDimensionFailsClosedUntilFreshRunRecovery() {
        OwnedVolume oldVolume = new OwnedVolume("active_region",
                ResourceKey.create(Registries.DIMENSION,
                        ResourceLocation.fromNamespaceAndPath("publicmines", "public_mine")),
                new BlockPos(-1024, -64, -1024), new BlockPos(1023, 319, 1023));
        ListTag volumes = new ListTag();
        volumes.add(oldVolume.save());
        CompoundTag saved = new CompoundTag();
        saved.putInt("schema", 3);
        saved.putIntArray("bounds", new int[] {-64, -64, 63, 63});
        saved.put("ownedVolumes", volumes);
        MineRunData oldRun = MineRunData.load(saved, null);

        String error = MineShellGeometry.persistedBoundsError(oldRun, 64);

        assertTrue(error.contains("older mine dimension"));
        assertTrue(error.contains("recover repair"));
    }
}
