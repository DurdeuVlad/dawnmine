package com.rusticcraft.publicmines.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Deterministic wall-clock schedule math for the daily reset (M7). */
class ResetScheduleTest {
    private static final ZoneId UTC = ZoneId.of("UTC");

    private static ZonedDateTime at(String iso) {
        return LocalDateTime.parse(iso).atZone(UTC);
    }

    @Test
    void lastOccurrenceBeforeAtAndAfterTheScheduledTime() {
        // Just before 06:00: the most recent occurrence is yesterday's 06:00.
        assertEquals(LocalDateTime.of(2026, 3, 9, 6, 0),
                ResetSchedule.lastOccurrence(at("2026-03-10T05:59:30"), 6, 0).toLocalDateTime());
        // Exactly at 06:00: today's occurrence is already due.
        assertEquals(LocalDateTime.of(2026, 3, 10, 6, 0),
                ResetSchedule.lastOccurrence(at("2026-03-10T06:00"), 6, 0).toLocalDateTime());
        // Long after: still today's occurrence, never tomorrow's.
        assertEquals(LocalDateTime.of(2026, 3, 10, 6, 0),
                ResetSchedule.lastOccurrence(at("2026-03-10T23:45"), 6, 0).toLocalDateTime());
        // A non-default schedule honours the configured hour/minute.
        assertEquals(LocalDateTime.of(2026, 3, 10, 4, 30),
                ResetSchedule.lastOccurrence(at("2026-03-10T04:30"), 4, 30).toLocalDateTime());
    }

    @Test
    void nextOccurrenceBeforeAtAndAfterTheScheduledTime() {
        assertEquals(LocalDateTime.of(2026, 3, 10, 6, 0),
                ResetSchedule.nextOccurrence(at("2026-03-10T05:59:30"), 6, 0).toLocalDateTime());
        // At the exact instant the reset is due, "next" is tomorrow so warnings
        // for an already-running reset never claim negative time.
        assertEquals(LocalDateTime.of(2026, 3, 11, 6, 0),
                ResetSchedule.nextOccurrence(at("2026-03-10T06:00"), 6, 0).toLocalDateTime());
        assertEquals(LocalDateTime.of(2026, 3, 11, 6, 0),
                ResetSchedule.nextOccurrence(at("2026-03-10T18:00"), 6, 0).toLocalDateTime());
    }

    @Test
    void occurrenceKeysAreStableAndDistinctPerScheduledInstant() {
        ZonedDateTime morning = ResetSchedule.lastOccurrence(at("2026-03-10T09:00"), 6, 0);
        ZonedDateTime nextDay = ResetSchedule.nextOccurrence(at("2026-03-10T09:00"), 6, 0);
        String key = ResetSchedule.key(morning);
        assertEquals(key, ResetSchedule.key(morning));
        assertNotEquals(key, ResetSchedule.key(nextDay));
        // The key is the local wall time, so a schedule edit legitimately
        // produces a different dedupe key.
        ZonedDateTime edited = ResetSchedule.lastOccurrence(at("2026-03-10T09:00"), 6, 30);
        assertNotEquals(key, ResetSchedule.key(edited));
    }

    @Test
    void dstGapAndOverlapKeepOneOccurrencePerLocalDate() {
        // America/New_York springs forward at 02:00 on 2026-03-08: 02:30 maps
        // to 03:30 EDT, and each local date still yields exactly one instant.
        ZoneId newYork = ZoneId.of("America/New_York");
        LocalDate springForward = LocalDate.of(2026, 3, 8);
        ZonedDateTime gap = ZonedDateTime.of(springForward, LocalTime.of(2, 30), newYork);
        assertEquals(LocalTime.of(3, 30), gap.toLocalTime());
        // The dedupe key records the resolved local time; still one key per
        // local date, so the DST gap cannot split an occurrence into two.
        assertEquals(LocalDateTime.of(2026, 3, 8, 3, 30), LocalDateTime.parse(ResetSchedule.key(gap)));

        // Falling back on 2026-11-01 takes the earlier offset deterministically.
        LocalDate fallBack = LocalDate.of(2026, 11, 1);
        ZonedDateTime overlap = ZonedDateTime.of(fallBack, LocalTime.of(1, 30), newYork);
        assertEquals(LocalTime.of(1, 30), overlap.toLocalTime());
        assertEquals(overlap, ZonedDateTime.of(fallBack, LocalTime.of(1, 30), newYork));
    }

    @Test
    void warningsDueFireOnceWhenTheThresholdIsCrossed() {
        ZonedDateTime next = at("2026-03-10T06:00");
        Set<Integer> sent = new HashSet<>();
        // 20 minutes out: nothing is due yet.
        assertTrue(ResetSchedule.warningsDue(
                List.of(15, 5, 1), sent::contains, at("2026-03-10T05:40"), next).isEmpty());
        // 15 minutes out: only the 15-minute warning.
        assertEquals(List.of(15), ResetSchedule.warningsDue(
                List.of(15, 5, 1), sent::contains, at("2026-03-10T05:45"), next));
        sent.add(15);
        // Between 15 and 5: nothing new.
        assertTrue(ResetSchedule.warningsDue(
                List.of(15, 5, 1), sent::contains, at("2026-03-10T05:30"), next).isEmpty());
        // At the 5-minute mark that offset fires in configured order.
        assertEquals(List.of(5), ResetSchedule.warningsDue(
                List.of(15, 5, 1), sent::contains, at("2026-03-10T05:55"), next));
        sent.add(5);
        // Inside the final minute the last offset fires; a repeated evaluation
        // delivers nothing twice.
        assertEquals(List.of(1), ResetSchedule.warningsDue(
                List.of(15, 5, 1), sent::contains, at("2026-03-10T05:59:30"), next));
        sent.add(1);
        assertTrue(ResetSchedule.warningsDue(
                List.of(15, 5, 1), sent::contains, at("2026-03-10T05:59:30"), next).isEmpty());
        // Once the occurrence has passed there is nothing left to warn about.
        assertTrue(ResetSchedule.warningsDue(
                List.of(15, 5, 1), w -> false, at("2026-03-10T06:00"), next).isEmpty());
    }

    @Test
    void warningsDueIgnoresInvalidEntries() {
        ZonedDateTime next = at("2026-03-10T06:00");
        List<Integer> configured = new java.util.ArrayList<>(List.of(10, 0, 5));
        configured.add(null);
        assertEquals(List.of(10, 5), ResetSchedule.warningsDue(
                configured, w -> false, at("2026-03-10T05:55"), next));
    }

    @Test
    void minutesRemainingRoundsUpAndNeverGoesNegative() {
        ZonedDateTime next = at("2026-03-10T06:00");
        assertEquals(15, ResetSchedule.minutesRemaining(at("2026-03-10T05:45:01"), next));
        assertEquals(15, ResetSchedule.minutesRemaining(at("2026-03-10T05:45:59"), next));
        assertEquals(1, ResetSchedule.minutesRemaining(at("2026-03-10T05:59:30"), next));
        assertEquals(0, ResetSchedule.minutesRemaining(at("2026-03-10T06:00:30"), next));
    }
}
