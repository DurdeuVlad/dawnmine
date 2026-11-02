package com.rusticcraft.publicmines.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class MineRunDataTest {

    @Test
    void topologyReceiptSurvivesSaveAndLoad() {
        MineRunData original = new MineRunData();
        original.beginNewRun("2026-11-02-1", 1, 42L, "2026-11-02");
        original.setBounds(-16, -16, 15, 15);
        original.setTopology("standard", "a".repeat(64), 42, 41);
        original.markStatus(RunStatus.READY);

        CompoundTag saved = original.save(new CompoundTag(), null);
        MineRunData restored = MineRunData.load(saved, null);

        assertEquals(4, restored.schema());
        assertEquals(RunStatus.READY, restored.status());
        assertEquals("standard", restored.topologyProfile());
        assertEquals("a".repeat(64), restored.topologyFingerprint());
        assertEquals(42, restored.topologyNodes());
        assertEquals(41, restored.topologyEdges());
    }

    @Test
    void legacyReadyRunWithoutTopologyReceiptFailsClosed() {
        MineRunData current = new MineRunData();
        current.beginNewRun("2026-11-02-1", 1, 42L, "2026-11-02");
        current.markStatus(RunStatus.READY);
        CompoundTag legacy = current.save(new CompoundTag(), null);
        legacy.putInt("schema", 1);
        legacy.remove("topologyProfile");
        legacy.remove("topologyFingerprint");
        legacy.remove("topologyNodes");
        legacy.remove("topologyEdges");

        MineRunData migrated = MineRunData.load(legacy, null);

        assertEquals(4, migrated.schema());
        assertEquals(RunStatus.FAILED, migrated.status());
        assertTrue(migrated.lastError().contains("/publicmine recover repair"));
    }

    @Test
    void futureSchemaFailsClosedAndIsPreservedVerbatim() {
        CompoundTag future = new CompoundTag();
        future.putInt("schema", 5);
        future.putString("status", RunStatus.READY.name());
        future.putString("futureField", "owned by newer mod version");
        CompoundTag original = future.copy();

        MineRunData loaded = MineRunData.load(future, null);

        assertEquals(5, loaded.schema());
        assertTrue(loaded.hasUnsupportedSchema());
        assertEquals(RunStatus.FAILED, loaded.status());
        assertTrue(loaded.lastError().contains("update Public Mines"));
        // Even if an older caller tries to mutate this record, serialization
        // must return the untouched future payload instead of downgrading it.
        loaded.markStatus(RunStatus.READY);
        assertEquals(original, loaded.save(new CompoundTag(), null));
    }

    @Test
    void malformedTopologyFingerprintFailsClosedAndHasSafeStatusSummary() {
        for (String fingerprint : new String[] {"short", "g".repeat(64), "A".repeat(64)}) {
            CompoundTag saved = new CompoundTag();
            saved.putInt("schema", 2);
            saved.putString("status", RunStatus.READY.name());
            saved.putString("topologyFingerprint", fingerprint);

            MineRunData loaded = MineRunData.load(saved, null);

            assertEquals(RunStatus.FAILED, loaded.status(), "bad fingerprint did not close entry");
            assertTrue(loaded.lastError().contains("/publicmine recover repair"));
            assertTrue(!loaded.hasValidTopologyFingerprint());
            assertEquals("(invalid)", loaded.topologyFingerprintPrefix(),
                    "status formatting must not substring a malformed receipt");
        }
    }

    @Test
    void emptyAndValidTopologyFingerprintsHaveSafeSummaries() {
        MineRunData empty = new MineRunData();
        assertEquals("(not planned)", empty.topologyFingerprintPrefix());

        MineRunData valid = new MineRunData();
        valid.setTopology("standard", "abcdef012345" + "0".repeat(52), 42, 41);
        assertEquals("abcdef012345", valid.topologyFingerprintPrefix());
    }

    @Test
    void schema2ReadyMigrationPreservesTopologyAndStartsWithEmptyGenerationProgress() {
        MineRunData original = readyRun();
        CompoundTag schema2 = original.save(new CompoundTag(), null);
        schema2.putInt("schema", 2);
        schema2.remove("generationPlanFingerprint");
        schema2.remove("pendingGenerationSegments");
        schema2.remove("completedGenerationSegments");
        schema2.remove("activeGenerationSegment");
        schema2.remove("activeGenerationCursor");

        MineRunData migrated = MineRunData.load(schema2, null);

        assertEquals(4, migrated.schema());
        assertEquals(RunStatus.READY, migrated.status());
        assertEquals("a".repeat(64), migrated.topologyFingerprint());
        assertEquals("", migrated.generationPlanFingerprint());
        assertTrue(migrated.pendingGenerationSegments().isEmpty());
        assertTrue(migrated.completedGenerationSegments().isEmpty());
        assertEquals("", migrated.activeGenerationSegment());
        assertEquals(0, migrated.activeGenerationCursor());
    }

    @Test
    void schema3ReadyMigrationStartsWithoutUnverifiableProtectionRecords() {
        MineRunData original = readyRun();
        CompoundTag schema3 = original.save(new CompoundTag(), null);
        schema3.putInt("schema", 3);
        schema3.remove("safeZones");
        schema3.remove("mineOreOrigins");

        MineRunData migrated = MineRunData.load(schema3, null);

        assertEquals(4, migrated.schema());
        assertEquals(RunStatus.READY, migrated.status());
        assertTrue(migrated.safeZones().isEmpty());
        assertTrue(migrated.mineOreOrigins().isEmpty());
    }

    @Test
    void generationQueueCursorAndCompletedSegmentsSurviveRoundTripInOrder() {
        MineRunData original = readyRun();
        assertTrue(original.beginGeneration("b".repeat(64)));
        assertTrue(original.enqueueGenerationSegment("edge:m000-m001"));
        assertTrue(original.enqueueGenerationSegment("edge:m001-m002"));
        assertEquals("edge:m000-m001", original.beginNextGenerationSegment());
        assertTrue(original.advanceGenerationCursor(12));
        assertTrue(original.completeActiveGenerationSegment());
        assertEquals("edge:m001-m002", original.beginNextGenerationSegment());
        assertTrue(original.advanceGenerationCursor(37));

        MineRunData restored = MineRunData.load(
                original.save(new CompoundTag(), null), null);

        assertEquals("b".repeat(64), restored.generationPlanFingerprint());
        assertEquals(List.of(), restored.pendingGenerationSegments());
        assertEquals(List.of("edge:m000-m001"), restored.completedGenerationSegments());
        assertEquals("edge:m001-m002", restored.activeGenerationSegment());
        assertEquals(37, restored.activeGenerationCursor());
    }

    @Test
    void generationPaletteSettingsArePinnedAcrossConfigChangesAndRestart() {
        MineRunData original = readyRun();
        assertTrue(original.beginGeneration("d".repeat(64), true, 512));

        MineRunData restored = MineRunData.load(
                original.save(new CompoundTag(), null), null);

        assertTrue(restored.generationNetherrackEnabled());
        assertEquals(512, restored.generationNetherrackInterval());
        assertFalse(restored.beginGeneration("d".repeat(64), true, 1024),
                "a config edit must apply to the next run, not mutate a partially generated mine");
        assertEquals(512, restored.generationNetherrackInterval());
    }

    @Test
    void generationEnqueueDeduplicatesAcrossPendingActiveAndCompletedStates() {
        MineRunData data = readyRun();
        assertTrue(data.beginGeneration("b".repeat(64)));
        assertTrue(data.enqueueGenerationSegment("edge:m000-m001"));
        assertFalse(data.enqueueGenerationSegment("edge:m000-m001"));
        assertEquals("edge:m000-m001", data.beginNextGenerationSegment());
        assertFalse(data.enqueueGenerationSegment("edge:m000-m001"));
        assertTrue(data.completeActiveGenerationSegment());
        assertFalse(data.enqueueGenerationSegment("edge:m000-m001"));
        assertFalse(data.enqueueGenerationSegment("bad id"));
        assertFalse(data.beginGeneration("c".repeat(64)),
                "progress must not be attached to a different plan fingerprint");
    }

    @Test
    void interruptedActiveGenerationSegmentReplaysFromCursorZero() {
        MineRunData data = readyRun();
        assertTrue(data.beginGeneration("b".repeat(64)));
        assertTrue(data.enqueueGenerationSegment("edge:m000-m001"));
        data.beginNextGenerationSegment();
        assertTrue(data.advanceGenerationCursor(200));

        data.restartActiveGenerationSegmentForReplay();

        assertEquals("edge:m000-m001", data.activeGenerationSegment());
        assertEquals(0, data.activeGenerationCursor());
        assertEquals(List.of(), data.completedGenerationSegments());
    }

    @Test
    void safeZonesAndOreOriginsPersistAcrossRestartAndClearOnNewRun() {
        MineRunData data = readyRun();
        OwnedVolume owned = ownedVolume("active_region", -16, -64, -16, 255, 320, 255);
        OwnedVolume safeZone = ownedVolume("checkpoint:station", 10, 20, 10, 20, 30, 20);
        BlockPos orePos = new BlockPos(32, 12, 32);
        ResourceLocation oreBlock = ResourceLocation.parse("minecraft:diamond_ore");
        data.addOwnedVolume(owned);

        assertTrue(data.registerSafeZone("checkpoint:station", safeZone));
        assertTrue(data.registerSafeZone("checkpoint:station",
                ownedVolume("retry-label", 10, 20, 10, 20, 30, 20)),
                "same ID and bounds are idempotent despite label changes");
        assertTrue(data.recordMineOre(MineManager.MINE_DIMENSION, orePos, oreBlock));
        assertTrue(data.recordMineOre(MineManager.MINE_DIMENSION, orePos, oreBlock),
                "same generated ore provenance is idempotent for replay");
        assertFalse(data.recordMineOre(MineManager.MINE_DIMENSION, orePos,
                ResourceLocation.parse("minecraft:coal_ore")), "a position cannot be rebound to a different ore");

        MineRunData restored = MineRunData.load(data.save(new CompoundTag(), null), null);

        assertEquals(1, restored.safeZones().size());
        assertTrue(restored.isInSafeZone(MineManager.MINE_DIMENSION, new BlockPos(15, 25, 15)));
        assertFalse(restored.isInSafeZone(MineManager.MINE_DIMENSION, new BlockPos(21, 25, 15)));
        assertTrue(restored.isRecordedMineOre(MineManager.MINE_DIMENSION, orePos, oreBlock));
        assertFalse(restored.isRecordedMineOre(MineManager.MINE_DIMENSION, orePos,
                ResourceLocation.parse("minecraft:coal_ore")));

        restored.beginNewRun("2026-11-03-1", 2, 99L, "2026-11-03");

        assertTrue(restored.safeZones().isEmpty());
        assertTrue(restored.mineOreOrigins().isEmpty());
        assertFalse(restored.isInSafeZone(MineManager.MINE_DIMENSION, new BlockPos(15, 25, 15)));
        assertFalse(restored.isRecordedMineOre(MineManager.MINE_DIMENSION, orePos, oreBlock));
    }

    @Test
    void protectionRegistrationRequiresOwnedMineBoundsAndRejectsConflicts() {
        MineRunData data = readyRun();
        data.addOwnedVolume(ownedVolume("active_region", -16, -64, -16, 255, 320, 255));
        OwnedVolume valid = ownedVolume("zone", 0, 0, 0, 15, 15, 15);
        OwnedVolume outside = ownedVolume("outside", 250, 0, 0, 270, 15, 15);
        ResourceKey<Level> otherDimension = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("minecraft:overworld"));
        OwnedVolume wrongDimension = new OwnedVolume("wrong", otherDimension,
                new BlockPos(0, 0, 0), new BlockPos(15, 15, 15));

        assertTrue(data.registerSafeZone("station:a", valid));
        assertFalse(data.registerSafeZone("station:a", outside), "one zone ID cannot be rebound");
        assertFalse(data.registerSafeZone("station:b", outside));
        assertFalse(data.registerSafeZone("station:c", wrongDimension));
        assertFalse(data.recordMineOre(MineManager.MINE_DIMENSION, new BlockPos(300, 1, 1),
                ResourceLocation.parse("minecraft:diamond_ore")));
    }

    @Test
    void malformedOrCrossRunProtectionRecordsFailClosed() {
        MineRunData data = readyRun();
        data.addOwnedVolume(ownedVolume("active_region", -16, -64, -16, 255, 320, 255));
        data.registerSafeZone("station:a", ownedVolume("zone", 0, 0, 0, 15, 15, 15));
        data.recordMineOre(MineManager.MINE_DIMENSION, new BlockPos(2, 2, 2),
                ResourceLocation.parse("minecraft:diamond_ore"));
        CompoundTag saved = data.save(new CompoundTag(), null);
        ListTag zones = saved.getList("safeZones", net.minecraft.nbt.Tag.TAG_COMPOUND);
        zones.getCompound(0).putString("runId", "stale-run-id");
        ListTag origins = saved.getList("mineOreOrigins", net.minecraft.nbt.Tag.TAG_COMPOUND);
        origins.getCompound(0).putString("block", "not a valid id");

        MineRunData restored = MineRunData.load(saved, null);

        assertTrue(restored.safeZones().isEmpty());
        assertTrue(restored.mineOreOrigins().isEmpty());
        assertFalse(restored.isInSafeZone(MineManager.MINE_DIMENSION, new BlockPos(5, 5, 5)));
    }

    @Test
    void tooManyPersistedOreOriginsFailTheRunClosed() {
        CompoundTag saved = new CompoundTag();
        saved.putInt("schema", 4);
        saved.putString("status", RunStatus.READY.name());
        saved.putString("runId", "2026-11-02-1");
        ListTag origins = new ListTag();
        for (int i = 0; i <= MineRunData.MAX_MINE_ORE_ORIGINS; i++) {
            origins.add(new CompoundTag());
        }
        saved.put("mineOreOrigins", origins);

        MineRunData restored = MineRunData.load(saved, null);

        assertEquals(RunStatus.FAILED, restored.status());
        assertTrue(restored.mineOreOrigins().isEmpty());
        assertTrue(restored.lastError().contains("provenance count exceeds"));
    }

    @Test
    void safeZoneRegistrationHasAHardCountLimit() {
        MineRunData data = readyRun();
        data.addOwnedVolume(ownedVolume("active_region", -16, -64, -16, 255, 320, 255));
        OwnedVolume zone = ownedVolume("zone", 0, 0, 0, 15, 15, 15);

        for (int i = 0; i < MineRunData.MAX_SAFE_ZONES; i++) {
            assertTrue(data.registerSafeZone("station:zone_" + i, zone));
        }

        assertFalse(data.registerSafeZone("station:too_many", zone));
        assertEquals(MineRunData.MAX_SAFE_ZONES, data.safeZones().size());
    }

    @Test
    void malformedGenerationProgressFailsClosedAndIsBounded() {
        CompoundTag saved = new CompoundTag();
        saved.putInt("schema", 3);
        saved.putString("status", RunStatus.READY.name());
        saved.putString("topologyFingerprint", "a".repeat(64));
        saved.putString("generationPlanFingerprint", "b".repeat(64));
        ListTag pending = new ListTag();
        for (int i = 0; i <= MineRunData.MAX_GENERATION_SEGMENTS; i++) {
            pending.add(StringTag.valueOf("edge:duplicate"));
        }
        saved.put("pendingGenerationSegments", pending);
        saved.putInt("activeGenerationCursor", MineRunData.MAX_GENERATION_OPERATION_CURSOR + 1);

        MineRunData loaded = MineRunData.load(saved, null);

        assertEquals(RunStatus.FAILED, loaded.status());
        assertTrue(loaded.lastError().contains("saved generation progress is invalid"));
        assertTrue(loaded.pendingGenerationSegments().size() <= MineRunData.MAX_GENERATION_SEGMENTS);
        assertEquals(0, loaded.activeGenerationCursor());
    }

    private static MineRunData readyRun() {
        MineRunData data = new MineRunData();
        data.beginNewRun("2026-11-02-1", 1, 42L, "2026-11-02");
        data.setTopology("standard", "a".repeat(64), 42, 41);
        data.markStatus(RunStatus.READY);
        return data;
    }

    private static OwnedVolume ownedVolume(String label, int minX, int minY, int minZ,
                                          int maxX, int maxY, int maxZ) {
        return new OwnedVolume(label, MineManager.MINE_DIMENSION,
                new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
    }
}
