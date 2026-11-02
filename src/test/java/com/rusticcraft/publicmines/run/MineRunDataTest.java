package com.rusticcraft.publicmines.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.CompoundTag;
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

        assertEquals(2, restored.schema());
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

        assertEquals(2, migrated.schema());
        assertEquals(RunStatus.FAILED, migrated.status());
        assertTrue(migrated.lastError().contains("/publicmine recover repair"));
    }

    @Test
    void futureSchemaFailsClosedAndIsPreservedVerbatim() {
        CompoundTag future = new CompoundTag();
        future.putInt("schema", 3);
        future.putString("status", RunStatus.READY.name());
        future.putString("futureField", "owned by newer mod version");
        CompoundTag original = future.copy();

        MineRunData loaded = MineRunData.load(future, null);

        assertEquals(3, loaded.schema());
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
}
