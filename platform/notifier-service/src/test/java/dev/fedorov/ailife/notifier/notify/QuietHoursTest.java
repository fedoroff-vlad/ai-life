package dev.fedorov.ailife.notifier.notify;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure logic for the quiet-hours window (#487 PX-1) — wrap-around, tz, and next-window-end. */
class QuietHoursTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    void sameDayWindowIsHalfOpen() {
        QuietHours q = new QuietHours(LocalTime.of(9, 0), LocalTime.of(17, 0), UTC);
        assertThat(q.isQuietAt(Instant.parse("2026-08-25T12:00:00Z"))).isTrue();
        assertThat(q.isQuietAt(Instant.parse("2026-08-25T09:00:00Z"))).isTrue();   // start inclusive
        assertThat(q.isQuietAt(Instant.parse("2026-08-25T17:00:00Z"))).isFalse();  // end exclusive
        assertThat(q.isQuietAt(Instant.parse("2026-08-25T08:59:00Z"))).isFalse();
    }

    @Test
    void windowWrappingMidnightCoversNight() {
        QuietHours q = new QuietHours(LocalTime.of(22, 0), LocalTime.of(8, 0), UTC);
        assertThat(q.isQuietAt(Instant.parse("2026-08-25T03:00:00Z"))).isTrue();   // 03:00 — deep in the night
        assertThat(q.isQuietAt(Instant.parse("2026-08-25T23:30:00Z"))).isTrue();   // late evening
        assertThat(q.isQuietAt(Instant.parse("2026-08-25T12:00:00Z"))).isFalse();  // midday
        assertThat(q.isQuietAt(Instant.parse("2026-08-25T08:00:00Z"))).isFalse();  // end exclusive
    }

    @Test
    void windowIsEvaluatedInTheUsersZone() {
        QuietHours q = new QuietHours(LocalTime.of(22, 0), LocalTime.of(8, 0), ZoneId.of("Europe/Moscow"));
        // 20:00Z == 23:00 Moscow (UTC+3) → inside; 10:00Z == 13:00 Moscow → outside.
        assertThat(q.isQuietAt(Instant.parse("2026-08-25T20:00:00Z"))).isTrue();
        assertThat(q.isQuietAt(Instant.parse("2026-08-25T10:00:00Z"))).isFalse();
    }

    @Test
    void nullOrEmptyWindowIsNeverQuiet() {
        assertThat(new QuietHours(null, LocalTime.of(8, 0), UTC).isQuietAt(Instant.now())).isFalse();
        assertThat(new QuietHours(LocalTime.of(8, 0), null, UTC).isQuietAt(Instant.now())).isFalse();
        assertThat(new QuietHours(LocalTime.of(8, 0), LocalTime.of(8, 0), UTC).isQuietAt(Instant.now())).isFalse();
    }

    @Test
    void nextWindowEndIsTheNextOccurrenceOfEnd() {
        QuietHours q = new QuietHours(LocalTime.of(22, 0), LocalTime.of(8, 0), UTC);
        // At 03:00 the window closes at 08:00 the same day.
        assertThat(q.nextWindowEnd(Instant.parse("2026-08-25T03:00:00Z")))
                .isEqualTo(Instant.parse("2026-08-25T08:00:00Z"));
        // At 23:00 today's 08:00 is already past, so the next close is tomorrow.
        assertThat(q.nextWindowEnd(Instant.parse("2026-08-25T23:00:00Z")))
                .isEqualTo(Instant.parse("2026-08-26T08:00:00Z"));
    }
}
