package com.dwurdy.dawnmine.run;

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
    void rebindingSurfaceExitReplacesTheOldSafeZone() {
        MineRunData data = readyRun();
        BlockPos old = new BlockPos(100, 65, 100);
        data.setSurfaceExit(old);
        assertTrue(data.registerSurfaceExitSafeZone());
        BlockPos replacement = new BlockPos(200, 65, 200);
        data.setSurfaceExit(replacement);
        assertTrue(data.registerSurfaceExitSafeZone());
        var zone = data.safeZone(MineRunData.surfaceExitSafeZoneId());
        assertTrue(zone.volume().contains(replacement));
        assertFalse(zone.volume().contains(old), "old overworld area must no longer be a safe zone");
        MineRunData restored = MineRunData.load(data.save(new CompoundTag(), null), null);
        assertEquals(replacement, restored.surfaceExit());
        assertTrue(restored.safeZone(MineRunData.surfaceExitSafeZoneId())
                .volume().contains(replacement));
    }

    @Test
    void topologyReceiptSurvivesSaveAndLoad() {
        MineRunData original = new MineRunData();
        original.beginNewRun("2026-11-02-1", 1, 42L, "2026-11-02");
        original.setBounds(-16, -16, 15, 15);
        original.setTopology("standard", "a".repeat(64), 42, 41);
        original.markStatus(RunStatus.READY);

        CompoundTag saved = original.save(new CompoundTag(), null);
        MineRunData restored = MineRunData.load(saved, null);

        assertEquals(9, restored.schema());
        assertEquals(RunStatus.READY, restored.status());
        assertEquals("standard", restored.topologyProfile());
        assertEquals("a".repeat(64), restored.topologyFingerprint());
        assertEquals(42, restored.topologyNodes());
        assertEquals(41, restored.topologyEdges());
    }

    @Test
    void topologySeedOffsetSurvivesSaveAndLoad() {
        // A run receipted at draw N must replay draw N forever after -
        // losing the offset would silently replan draw 0 and fork the
        // saved topology fingerprint.
        MineRunData original = new MineRunData();
        original.beginNewRun("2026-11-02-1", 1, 42L, "2026-11-02");
        original.setBounds(-16, -16, 15, 15);
        original.setTopology("standard", "a".repeat(64), 42, 41, 3);
        original.markStatus(RunStatus.READY);

        CompoundTag saved = original.save(new CompoundTag(), null);
        MineRunData restored = MineRunData.load(saved, null);

        assertEquals(3, restored.topologySeedOffset());
        // A record serialised before the field existed carries no tag and
        // must load as draw 0 - identical to pre-draw semantics.
        CompoundTag legacy = original.save(new CompoundTag(), null);
        legacy.remove("topologySeedOffset");
        assertEquals(0, MineRunData.load(legacy, null).topologySeedOffset());
    }

    @Test
    void mineRewardClaimIsTrackedOncePersistsAndClearsWithRun() {
        MineRunData data = readyRun();
        OwnedVolume owned = ownedVolume("reward_region", -16, -64, -16, 255, 320, 255);
        data.addOwnedVolume(owned);
        java.util.UUID mob = java.util.UUID.randomUUID();
        assertTrue(data.registerMineEntity(mob, ResourceLocation.parse("minecraft:zombie"),
                MineManager.MINE_DIMENSION, new BlockPos(4, 20, 4), "encounter:test:node", "upper"));

        assertTrue(data.claimMineReward(mob));
        assertFalse(data.claimMineReward(mob), "same encounter cannot claim twice");
        assertTrue(data.unregisterMineEntity(mob));

        MineRunData restored = MineRunData.load(data.save(new CompoundTag(), null), null);
        assertTrue(restored.hasClaimedMineReward(mob), "death claim must outlive provenance unregister");
        assertFalse(restored.claimMineReward(mob), "untracked stale UUID cannot claim after reload");
        restored.beginNewRun("2026-11-03-2", 2, 84L, "2026-11-03");
        assertFalse(restored.hasClaimedMineReward(mob), "claims are scoped to one daily run");
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

        assertEquals(9, migrated.schema());
        assertEquals(RunStatus.FAILED, migrated.status());
        assertTrue(migrated.lastError().contains("/dawnmine recover repair"));
    }

    @Test
    void futureSchemaFailsClosedAndIsPreservedVerbatim() {
        CompoundTag future = new CompoundTag();
        future.putInt("schema", 10);
        future.putString("status", RunStatus.READY.name());
        future.putString("futureField", "owned by newer mod version");
        CompoundTag original = future.copy();

        MineRunData loaded = MineRunData.load(future, null);

        assertEquals(10, loaded.schema());
        assertTrue(loaded.hasUnsupportedSchema());
        assertEquals(RunStatus.FAILED, loaded.status());
        assertTrue(loaded.lastError().contains("update Dawnmine"));
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
            assertTrue(loaded.lastError().contains("/dawnmine recover repair"));
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

        assertEquals(9, migrated.schema());
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

        assertEquals(9, migrated.schema());
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
    void generatedCheckpointStationsPersistSharedAccessAndClearTheirZoneOnReset() {
        MineRunData data = readyRun();
        data.addOwnedVolume(ownedVolume("active_region", -16, -64, -16, 255, 320, 255));
        BlockPos arrival = new BlockPos(40, 20, 40);
        OwnedVolume zone = ownedVolume("checkpoint_cp_01_safe_zone", 38, 19, 38, 42, 22, 42);

        assertTrue(data.registerCheckpointStation("cp_01", "Upper checkpoint", "upper", 70, arrival, zone));
        assertTrue(data.registerCheckpointStation("cp_01", "Upper checkpoint", "upper", 70, arrival, zone),
                "generation replay is idempotent");
        assertFalse(data.registerCheckpointStation("cp_01", "Rebound", "upper", 70,
                new BlockPos(50, 20, 50), zone), "station IDs cannot be rebound");
        assertFalse(data.registerCheckpointStation("bad/id", "Invalid", "upper", 1, arrival, zone));
        assertFalse(data.registerCheckpointStation("cp_bad", "Invalid", "upper", 1, arrival,
                ownedVolume("not_five_wide", 39, 19, 38, 42, 22, 42)));

        MineRunData restored = MineRunData.load(data.save(new CompoundTag(), null), null);
        assertEquals(List.of(data.checkpointStation("cp_01")), restored.checkpointStations());
        assertEquals("upper", restored.checkpointStation("cp_01").region());
        assertTrue(restored.isInSafeZone(MineManager.MINE_DIMENSION, arrival));
        assertFalse(restored.isInSafeZone(MineManager.MINE_DIMENSION, new BlockPos(43, 20, 40)),
                "the zone ends at the generated 5x5 station pad bounds");

        restored.beginNewRun("2026-11-03-2", 2, 99L, "2026-11-03");
        assertTrue(restored.checkpointStations().isEmpty());
        assertTrue(restored.safeZones().isEmpty());
    }

    @Test
    void checkpointRegistryIsBoundedAndWrongNbtTypeFailsClosed() {
        MineRunData data = readyRun();
        data.addOwnedVolume(ownedVolume("active_region", -16, -64, -16, 255, 320, 255));
        for (int index = 0; index < MineRunData.MAX_CHECKPOINT_STATIONS; index++) {
            int x = 10 + (index % 16) * 6;
            int z = 10 + (index / 16) * 6;
            BlockPos arrival = new BlockPos(x, 20, z);
            OwnedVolume zone = ownedVolume("cp_" + index, x - 2, 19, z - 2, x + 2, 22, z + 2);
            assertTrue(data.registerCheckpointStation("cp_" + index, "Checkpoint " + index,
                    "upper", 10, arrival, zone));
        }
        BlockPos extra = new BlockPos(210, 20, 210);
        assertFalse(data.registerCheckpointStation("too_many", "Overflow", "upper", 10, extra,
                ownedVolume("extra", 208, 19, 208, 212, 22, 212)));
        assertEquals(MineRunData.MAX_CHECKPOINT_STATIONS, data.checkpointStations().size());

        CompoundTag malformed = readyRun().save(new CompoundTag(), null);
        malformed.putString("checkpointStations", "wrong-type");
        MineRunData loaded = MineRunData.load(malformed, null);
        assertEquals(RunStatus.FAILED, loaded.status());
        assertTrue(loaded.lastError().contains("checkpoint registry has the wrong NBT type"));
    }

    @Test
    void surfaceExitSafeZoneIsFixedToRecordedOverworldPadAndRejectsOtherAreas() {
        MineRunData data = readyRun();
        BlockPos exit = new BlockPos(0, 64, 0);
        data.setSurfaceExit(exit);

        assertTrue(data.registerSurfaceExitSafeZone());
        assertTrue(data.hasSafeZonesInDimension(Level.OVERWORLD));
        assertTrue(data.isInSafeZone(Level.OVERWORLD, exit));
        assertTrue(data.isInSafeZone(Level.OVERWORLD, exit.offset(2, 2, 2)),
                "the fixed station-sized volume includes its exact outer block corner");
        assertFalse(data.isInSafeZone(Level.OVERWORLD, exit.offset(3, 0, 0)),
                "the volume does not include an arbitrary radius");
        assertFalse(data.isInSafeZone(MineManager.MINE_DIMENSION, exit),
                "the external zone is dimension-qualified");
        assertFalse(data.registerSafeZone("dawnmine:custom_overworld_zone",
                new OwnedVolume("outside", Level.OVERWORLD, exit.offset(-2, -1, -2), exit.offset(2, 2, 2))),
                "generic zone registration cannot target arbitrary overworld areas");
        assertFalse(data.registerSafeZone("dawnmine:custom_nether_zone",
                new OwnedVolume("outside", Level.NETHER, exit.offset(-2, -1, -2), exit.offset(2, 2, 2))));

        MineRunData restored = MineRunData.load(data.save(new CompoundTag(), null), null);
        assertTrue(restored.isInSafeZone(Level.OVERWORLD, exit));
        assertTrue(restored.hasSafeZonesInDimension(Level.OVERWORLD));
        restored.beginNewRun("2026-11-03-3", 3, 100L, "2026-11-03");
        assertFalse(restored.hasSafeZonesInDimension(Level.OVERWORLD),
                "the surface exit protection belongs to one active run");

        MineRunData missingExit = readyRun();
        assertFalse(missingExit.registerSurfaceExitSafeZone(),
                "there is no public surface zone until the shared exit has been recorded");
    }

    @Test
    void mineEntitiesAndEncounterSiteClaimsPersistAndClearOnNewRun() {
        MineRunData data = readyRun();
        data.addOwnedVolume(ownedVolume("active_region", -16, -64, -16, 255, 320, 255));
        var uuid = java.util.UUID.fromString("b4f288d7-9574-4a73-8dbf-e4e008d56a23");
        var entityType = ResourceLocation.parse("born_in_chaos_v1:diamond_termite");
        BlockPos pos = new BlockPos(32, -24, 32);

        assertTrue(data.registerMineEntity(uuid, entityType, MineManager.MINE_DIMENSION,
                pos, "node:m042", "chaos"));
        assertTrue(data.registerMineEntity(uuid, entityType, MineManager.MINE_DIMENSION,
                pos, "node:m042", "chaos"), "repeated entity ownership registration is idempotent");
        assertFalse(data.registerMineEntity(uuid, ResourceLocation.parse("minecraft:zombie"),
                MineManager.MINE_DIMENSION, pos, "node:m042", "chaos"),
                "an entity UUID cannot be rebound to different ownership metadata");
        assertTrue(data.claimEncounterSite("node:m042"));
        assertFalse(data.claimEncounterSite("node:m042"), "site claim prevents duplicate encounter spawning");

        MineRunData restored = MineRunData.load(data.save(new CompoundTag(), null), null);

        assertTrue(restored.isTrackedMineEntity(uuid));
        assertEquals(data.mineEntities(), restored.mineEntities());
        assertTrue(restored.hasClaimedEncounterSite("node:m042"));
        assertFalse(restored.claimEncounterSite("node:m042"));
        assertTrue(restored.unregisterMineEntity(uuid));
        assertFalse(restored.isTrackedMineEntity(uuid));

        restored.beginNewRun("2026-11-03-1", 2, 99L, "2026-11-03");

        assertTrue(restored.mineEntities().isEmpty());
        assertFalse(restored.hasClaimedEncounterSite("node:m042"));
        assertTrue(restored.claimEncounterSite("node:m042"));
    }

    @Test
    void mineEntityRegistrationRequiresAnOwnedMinePositionAndValidMetadata() {
        MineRunData data = readyRun();
        data.addOwnedVolume(ownedVolume("active_region", -16, -64, -16, 255, 320, 255));
        var uuid = java.util.UUID.fromString("b4f288d7-9574-4a73-8dbf-e4e008d56a23");
        var entityType = ResourceLocation.parse("born_in_chaos_v1:diamond_termite");
        ResourceKey<Level> overworld = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("minecraft:overworld"));

        assertFalse(data.registerMineEntity(uuid, entityType, MineManager.MINE_DIMENSION,
                new BlockPos(300, 1, 1), "node:m042", "chaos"));
        assertFalse(data.registerMineEntity(uuid, entityType, overworld,
                new BlockPos(32, -24, 32), "node:m042", "chaos"));
        assertFalse(data.registerMineEntity(uuid, entityType, MineManager.MINE_DIMENSION,
                new BlockPos(32, -24, 32), "", "chaos"));
        assertFalse(data.claimEncounterSite("bad site id"));
    }

    @Test
    void schemaFourMigrationDropsUnverifiableMineEntityAndEncounterState() {
        MineRunData data = readyRun();
        data.addOwnedVolume(ownedVolume("active_region", -16, -64, -16, 255, 320, 255));
        var uuid = java.util.UUID.fromString("b4f288d7-9574-4a73-8dbf-e4e008d56a23");
        data.registerMineEntity(uuid, ResourceLocation.parse("minecraft:zombie"),
                MineManager.MINE_DIMENSION, new BlockPos(1, 1, 1), "node:m001", "upper");
        data.claimEncounterSite("node:m001");
        CompoundTag legacy = data.save(new CompoundTag(), null);
        legacy.putInt("schema", 4);

        MineRunData migrated = MineRunData.load(legacy, null);

        assertEquals(9, migrated.schema());
        assertTrue(migrated.mineEntities().isEmpty());
        assertFalse(migrated.hasClaimedEncounterSite("node:m001"));
    }

    @Test
    void schemaFiveMigrationKeepsSiteClaimsButDropsOriginsWithoutSavedPositions() {
        MineRunData data = readyRun();
        data.addOwnedVolume(ownedVolume("active_region", -16, -64, -16, 255, 320, 255));
        var uuid = java.util.UUID.fromString("b4f288d7-9574-4a73-8dbf-e4e008d56a23");
        data.registerMineEntity(uuid, ResourceLocation.parse("minecraft:zombie"),
                MineManager.MINE_DIMENSION, new BlockPos(32, -24, 32), "node:m001", "upper");
        data.claimEncounterSite("node:m001");
        CompoundTag legacy = data.save(new CompoundTag(), null);
        legacy.putInt("schema", 5);
        legacy.getList("mineEntities", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).remove("pos");

        MineRunData migrated = MineRunData.load(legacy, null);

        assertEquals(9, migrated.schema());
        assertTrue(migrated.mineEntities().isEmpty());
        assertTrue(migrated.hasClaimedEncounterSite("node:m001"));
    }

    @Test
    void malformedMineEntityRecordsAreIgnoredAndTooManyFailClosed() {
        MineRunData data = readyRun();
        CompoundTag saved = data.save(new CompoundTag(), null);
        ListTag records = new ListTag();
        CompoundTag malformed = new CompoundTag();
        malformed.putString("uuid", "not-a-uuid");
        records.add(malformed);
        saved.put("mineEntities", records);
        MineRunData loaded = MineRunData.load(saved, null);
        assertTrue(loaded.mineEntities().isEmpty());

        ListTag oversized = new ListTag();
        for (int i = 0; i <= MineRunData.MAX_MINE_ENTITIES; i++) {
            oversized.add(new CompoundTag());
        }
        saved.put("mineEntities", oversized);
        MineRunData capped = MineRunData.load(saved, null);
        assertEquals(RunStatus.FAILED, capped.status());
        assertTrue(capped.lastError().contains("mine-entity count exceeds"));
        assertTrue(capped.mineEntities().isEmpty());
    }

    @Test
    void malformedEncounterClaimDataFailsClosedAndIsBounded() {
        CompoundTag saved = readyRun().save(new CompoundTag(), null);
        ListTag claims = new ListTag();
        claims.add(new CompoundTag());
        saved.put("encounterSiteClaims", claims);

        MineRunData malformed = MineRunData.load(saved, null);

        assertEquals(RunStatus.FAILED, malformed.status());
        assertTrue(malformed.lastError().contains("non-string"));

        ListTag tooMany = new ListTag();
        for (int i = 0; i <= MineRunData.MAX_ENCOUNTER_SITE_CLAIMS; i++) {
            tooMany.add(StringTag.valueOf("node:m001"));
        }
        saved.put("encounterSiteClaims", tooMany);
        MineRunData capped = MineRunData.load(saved, null);
        assertEquals(RunStatus.FAILED, capped.status());
        assertTrue(capped.lastError().contains("claim count exceeds"));
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

    // ------------------------------------------------------------------
    // Daily reset transaction (schema 9)
    // ------------------------------------------------------------------

    @Test
    void dailyResetTransactionSurvivesSaveLoadAndCompletesOnce() {
        MineRunData data = readyRun();
        data.beginDailyReset("2026-11-03T06:00");
        data.setResetCursor(37);
        data.noteResetAttempt();
        data.noteResetAttempt();
        data.markStatus(RunStatus.RESETTING);

        MineRunData resumed = MineRunData.load(data.save(new CompoundTag(), null), null);

        // A restart mid-cleanup resumes the same persisted transaction.
        assertEquals(ResetPhase.CLEANING, resumed.resetPhase());
        assertEquals("2026-11-03T06:00", resumed.resetDue());
        assertEquals(37, resumed.resetCursor());
        assertEquals(2, resumed.resetAttempts());
        assertEquals(RunStatus.RESETTING, resumed.status());
        assertTrue(resumed.resetInProgress());

        resumed.markResetFinalizing();
        resumed.completeDailyReset("2026-11-03T06:00");
        MineRunData done = MineRunData.load(resumed.save(new CompoundTag(), null), null);

        assertEquals(ResetPhase.NONE, done.resetPhase());
        assertFalse(done.resetInProgress());
        assertEquals("", done.resetDue());
        assertEquals(0, done.resetCursor());
        assertEquals(0, done.resetAttempts());
        // The completed occurrence is the idempotency dedupe for scheduling.
        assertEquals("2026-11-03T06:00", done.lastResetDue());
    }

    @Test
    void resetPhaseSurvivesNewRunCreationInsideTheTransaction() {
        MineRunData data = readyRun();
        data.beginDailyReset("2026-11-03T06:00");
        data.markResetFinalizing();

        // FINALIZING rebuilds the run through the normal initialization path;
        // the in-flight phase must survive beginNewRun so an interruption is
        // resumed instead of duplicated.
        data.beginNewRun("2026-11-03-2", 2, 84L, "2026-11-03");

        assertEquals(ResetPhase.FINALIZING, data.resetPhase());
        assertEquals("2026-11-03T06:00", data.resetDue());
        MineRunData restored = MineRunData.load(data.save(new CompoundTag(), null), null);
        assertEquals(ResetPhase.FINALIZING, restored.resetPhase());
        assertTrue(restored.resetInProgress());
    }

    @Test
    void syntheticResetKeyDoesNotConsumeTheScheduledOccurrence() {
        MineRunData data = readyRun();
        data.noteResetDueConsumed("2026-11-03T06:00");
        data.beginDailyReset("manual:2026-11-03T10:12:34");

        data.completeDailyReset("manual:2026-11-03T10:12:34");

        // A manual (or staff-injected) transaction never rewrites the record
        // that today's scheduled occurrence already ran; without this the
        // scheduler would immediately reset that occurrence a second time.
        assertEquals("2026-11-03T06:00", data.lastResetDue());
        assertEquals(ResetPhase.NONE, data.resetPhase());

        // A real scheduled key still updates the dedupe record.
        data.beginDailyReset("2026-11-04T06:00");
        data.completeDailyReset("2026-11-04T06:00");
        assertEquals("2026-11-04T06:00", data.lastResetDue());
    }

    @Test
    void consumedOccurrenceWithoutRunResetIsPersisted() {
        MineRunData data = new MineRunData();
        data.noteResetDueConsumed("2026-11-03T06:00");

        MineRunData restored = MineRunData.load(data.save(new CompoundTag(), null), null);

        assertEquals("2026-11-03T06:00", restored.lastResetDue());
        assertFalse(restored.resetInProgress());
        assertEquals(ResetPhase.NONE, restored.resetPhase());
    }

    @Test
    void malformedResetStateFailsClosed() {
        CompoundTag wrongPhaseType = readyRun().save(new CompoundTag(), null);
        wrongPhaseType.putString("resetPhase", "CLEANING");
        MineRunData loaded = MineRunData.load(wrongPhaseType, null);
        assertEquals(RunStatus.FAILED, loaded.status());
        assertTrue(loaded.lastError().contains("reset phase has the wrong NBT type"));

        CompoundTag outOfRange = readyRun().save(new CompoundTag(), null);
        outOfRange.putInt("resetPhase", 99);
        loaded = MineRunData.load(outOfRange, null);
        assertEquals(RunStatus.FAILED, loaded.status());
        assertTrue(loaded.lastError().contains("reset phase is out of range"));

        CompoundTag wrongDueType = readyRun().save(new CompoundTag(), null);
        wrongDueType.putInt("resetDue", 7);
        loaded = MineRunData.load(wrongDueType, null);
        assertEquals(RunStatus.FAILED, loaded.status());
        assertTrue(loaded.lastError().contains("reset schedule keys have the wrong NBT type"));
    }

    @Test
    void resetPhaseWithoutARunFailsClosedAndStrayFieldsAreReset() {
        // A live phase with no run to own it is inconsistent: fail closed so
        // staff inspect instead of silently resuming cleanup over nothing.
        CompoundTag saved = new MineRunData().save(new CompoundTag(), null);
        saved.putInt("resetPhase", 1); // CLEANING on an UNINITIALIZED record
        MineRunData loaded = MineRunData.load(saved, null);
        assertEquals(RunStatus.FAILED, loaded.status());
        assertTrue(loaded.lastError().contains("no run to resume"));

        // A NONE phase with stray transaction fields is normalized away.
        CompoundTag stray = readyRun().save(new CompoundTag(), null);
        stray.putInt("resetPhase", 0);
        stray.putString("resetDue", "2026-11-03T06:00");
        stray.putInt("resetCursor", 12);
        stray.putInt("resetAttempts", 3);
        loaded = MineRunData.load(stray, null);
        assertEquals(RunStatus.READY, loaded.status());
        assertFalse(loaded.resetInProgress());
        assertEquals("", loaded.resetDue());
        assertEquals(0, loaded.resetCursor());
        assertEquals(0, loaded.resetAttempts());
    }

    @Test
    void schemaEightRecordLoadsWithNoResetTransaction() {
        CompoundTag legacy = readyRun().save(new CompoundTag(), null);
        legacy.putInt("schema", 8);
        MineRunData migrated = MineRunData.load(legacy, null);

        assertEquals(9, migrated.schema());
        assertEquals(RunStatus.READY, migrated.status());
        assertEquals(ResetPhase.NONE, migrated.resetPhase());
        assertFalse(migrated.resetInProgress());
        assertEquals("", migrated.lastResetDue());
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
