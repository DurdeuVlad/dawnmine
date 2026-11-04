package com.dwurdy.dawnmine.run;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure wall-clock math for the daily mine reset. Kept free of Minecraft types
 * so the scheduling contract can be unit-tested deterministically.
 *
 * <p>The reset time is a local wall time ({@code resetHour:resetMinute}) in the
 * configured zone. An "occurrence" is one scheduled instant per local date;
 * {@link ZonedDateTime#of(LocalDate, LocalTime, java.time.ZoneId)} resolves DST
 * gaps by shifting forward and overlaps by taking the earlier offset, which is
 * the intended deterministic behaviour for a fixed wall-clock schedule.
 */
public final class ResetSchedule {
    private ResetSchedule() {}

    /** The most recent scheduled reset instant at or before {@code now}. */
    public static ZonedDateTime lastOccurrence(ZonedDateTime now, int hour, int minute) {
        LocalTime time = LocalTime.of(hour, minute);
        ZonedDateTime today = ZonedDateTime.of(now.toLocalDate(), time, now.getZone());
        return now.isBefore(today)
                ? ZonedDateTime.of(now.toLocalDate().minusDays(1), time, now.getZone())
                : today;
    }

    /** The next scheduled reset instant strictly after {@code now}. */
    public static ZonedDateTime nextOccurrence(ZonedDateTime now, int hour, int minute) {
        LocalTime time = LocalTime.of(hour, minute);
        ZonedDateTime today = ZonedDateTime.of(now.toLocalDate(), time, now.getZone());
        return now.isBefore(today)
                ? today
                : ZonedDateTime.of(now.toLocalDate().plusDays(1), time, now.getZone());
    }

    /** Stable dedupe key for one scheduled occurrence: its local wall time.
     * Two different scheduled instants never share a key for a fixed schedule;
     * a schedule edit legitimately produces a new key. */
    public static String key(ZonedDateTime occurrence) {
        return occurrence.toLocalDateTime().toString();
    }

    /** Whether {@code key} names a real scheduled occurrence. Synthetic
     * transaction keys (e.g. {@code "manual:..."} operator resets, staff
     * recovery values) are deliberately not occurrence keys, so completing a
     * synthetic transaction can never erase the record that a scheduled
     * occurrence already ran. */
    public static boolean isOccurrenceKey(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        try {
            LocalDateTime.parse(key);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Configured warning offsets (minutes before reset) whose threshold has
     * been crossed at {@code now} for the upcoming {@code next} occurrence and
     * that have not already been delivered. Returns them in configured order. */
    public static List<Integer> warningsDue(List<Integer> configured, SetView sent,
                                            ZonedDateTime now, ZonedDateTime next) {
        List<Integer> due = new ArrayList<>();
        if (!next.isAfter(now)) {
            return due;
        }
        long secondsUntil = Duration.between(now, next).getSeconds();
        for (Integer warn : configured) {
            if (warn == null || warn <= 0 || sent.contains(warn)) {
                continue;
            }
            if (secondsUntil <= warn * 60L) {
                due.add(warn);
            }
        }
        return due;
    }

    /** Whole minutes remaining until {@code next} from {@code now}, rounded up
     * so messages never claim more time than is actually left. */
    public static long minutesRemaining(ZonedDateTime now, ZonedDateTime next) {
        long seconds = Duration.between(now, next).getSeconds();
        return Math.max(0, (seconds + 59) / 60);
    }

    /** Minimal sent-marker view so the helper stays collection-agnostic. */
    public interface SetView {
        boolean contains(int warningMinutes);
    }
}
