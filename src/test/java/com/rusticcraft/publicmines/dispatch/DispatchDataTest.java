package com.rusticcraft.publicmines.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

class DispatchDataTest {
    @Test
    void rejectsDuplicateConcurrentSessionAndReleasesForReconnect() {
        DispatchData data = new DispatchData();
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertTrue(data.acquire(owner));
        assertFalse(data.acquire(owner));
        // A second player's simultaneous session is independent and must succeed.
        assertTrue(data.acquire(other));
        data.release(owner);
        assertTrue(data.acquire(owner));
        data.release(other);
    }

    @Test
    void retainSessionsDropsOrphanedLocksAndKeepsOpenOnes() {
        DispatchData data = new DispatchData();
        UUID open = UUID.randomUUID();
        UUID orphaned = UUID.randomUUID();
        assertTrue(data.acquire(open));
        assertTrue(data.acquire(orphaned));

        // Simulate a disconnect where removed() never ran for the orphaned menu.
        data.retainSessions(java.util.Set.of(open));
        assertFalse(data.acquire(open));
        assertTrue(data.acquire(orphaned));
    }

    @Test
    void malformedAndFutureSchemaFailClosedWithoutChangingSavedBytes() {
        CompoundTag malformed = new CompoundTag();
        malformed.putInt("schema", 1);
        malformed.putString("players", "not a list");
        assertPreserved(malformed);

        CompoundTag wrongElementType = new CompoundTag();
        wrongElementType.putInt("schema", 1);
        net.minecraft.nbt.ListTag strings = new net.minecraft.nbt.ListTag();
        strings.add(StringTag.valueOf("not a player record"));
        wrongElementType.put("players", strings);
        assertPreserved(wrongElementType);

        CompoundTag future = new CompoundTag();
        future.putInt("schema", 99);
        future.put("opaque", StringTag.valueOf("future value"));
        assertPreserved(future);
    }

    private static void assertPreserved(CompoundTag original) {
        CompoundTag before = original.copy();
        DispatchData loaded = DispatchData.load(original, null);
        assertFalse(loaded.isAvailable());
        assertEquals(before, loaded.save(new CompoundTag(), null));
        assertThrows(IllegalStateException.class, () -> loaded.contents(UUID.randomUUID()));
    }
}
