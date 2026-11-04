package com.dwurdy.dawnmine.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class TeleporterDataTest {
    @Test
    void nullInputNeverCreatesPartialBinding() {
        var data = new TeleporterData();
        assertFalse(data.bind(new BlockPos(1, 64, 1), null));
        assertNull(data.binding());
        assertFalse(data.bind(null, new BlockPos(2, 64, 1)));
        assertNull(data.binding());
        var different = new BlockPos(5, 64, 5);
        assertTrue(data.bind(different, different.east()));
        assertEquals(different, data.binding().lodestone());
    }

    @Test
    void bindingRoundTripsAndCannotSilentlyReplaceAnotherConsole() {
        var data = new TeleporterData();
        var console = new BlockPos(100, 65, 100);
        var landing = console.south();
        assertTrue(data.bind(console, landing));
        assertFalse(data.bind(console.east(), landing));
        var restored = TeleporterData.load(data.save(new CompoundTag(), null), null);
        assertEquals(new TeleporterData.Binding(console, landing), restored.binding());
        restored.unbind();
        assertNull(TeleporterData.load(restored.save(new CompoundTag(), null), null).binding());
    }

    @Test
    void incompleteSavedBindingStaysInactive() {
        var malformed = new CompoundTag();
        malformed.putIntArray("lodestone", new int[] {1, 2, 3});
        TeleporterData data = TeleporterData.load(malformed, null);
        assertNull(data.binding());
        // The discarded partial binding must not block a fresh different
        // console from binding, and the fresh binding must round-trip.
        assertTrue(data.bind(new BlockPos(5, 64, 5), new BlockPos(6, 64, 5)));
        assertEquals(new BlockPos(5, 64, 5), data.binding().lodestone());
        assertEquals(new BlockPos(6, 64, 5), data.binding().safeLanding());
        // Null input is rejected before mutating anything.
        assertFalse(data.bind(null, new BlockPos(0, 0, 0)));
        assertFalse(data.bind(new BlockPos(0, 0, 0), null));
        assertEquals(new BlockPos(5, 64, 5), data.binding().lodestone());
    }
}
