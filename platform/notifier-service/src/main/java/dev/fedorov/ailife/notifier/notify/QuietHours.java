package dev.fedorov.ailife.notifier.notify;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * A user's quiet-hours window (#487 PX-1): a local wall-clock interval {@code [start, end)} in zone
 * {@code tz}, possibly wrapping midnight (when {@code start > end}, e.g. 22:00 → 08:00). Pure value
 * logic, unit-tested; {@link NotificationGate} evaluates it at send time to decide whether a proactive
 * push is held.
 */
public record QuietHours(LocalTime start, LocalTime end, ZoneId tz) {

    /** True when {@code now}, read as local time in {@code tz}, falls inside the window. */
    public boolean isQuietAt(Instant now) {
        if (start == null || end == null || start.equals(end)) {
            return false; // no (or empty) window configured
        }
        LocalTime t = ZonedDateTime.ofInstant(now, tz).toLocalTime();
        if (start.isBefore(end)) {
            return !t.isBefore(start) && t.isBefore(end);   // [start, end) within one day
        }
        return !t.isBefore(start) || t.isBefore(end);       // window wraps midnight
    }

    /**
     * The next instant the window closes at-or-strictly-after {@code now}: the next occurrence of the
     * local {@code end} time in {@code tz}. Called while quiet, this is when the held message may be
     * delivered. DST-safe (via {@link ZonedDateTime}).
     */
    public Instant nextWindowEnd(Instant now) {
        ZonedDateTime candidate = ZonedDateTime.ofInstant(now, tz).with(end);
        if (!candidate.toInstant().isAfter(now)) {
            candidate = candidate.plusDays(1);
        }
        return candidate.toInstant();
    }
}
