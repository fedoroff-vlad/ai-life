package dev.fedorov.ailife.mcp.briefing.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit cover for {@link BriefingMcpTools#toUtcDailyCron} — the local "HH:mm" +
 * IANA zone → UTC daily Spring-cron conversion (BR-f2). No Spring context; the
 * method is pure. scheduler-service evaluates cron in UTC, so the conversion is
 * the wire-critical bit.
 */
class CronConversionTest {

    @Test
    void convertsLocalTimeToUtcCron() {
        // Europe/Moscow is UTC+3 year-round (no DST): 08:00 local → 05:00 UTC.
        assertThat(BriefingMcpTools.toUtcDailyCron("08:00", "Europe/Moscow"))
                .isEqualTo("0 0 5 * * *");
    }

    @Test
    void nullTimezoneIsTreatedAsUtc() {
        assertThat(BriefingMcpTools.toUtcDailyCron("06:30", null))
                .isEqualTo("0 30 6 * * *");
    }

    @Test
    void wrapsBackAcrossMidnightWhenZoneIsAheadOfUtc() {
        // 01:00 in UTC+3 is 22:00 UTC the previous day — the hour, not the date, is what the cron carries.
        assertThat(BriefingMcpTools.toUtcDailyCron("01:00", "Europe/Moscow"))
                .isEqualTo("0 0 22 * * *");
    }
}
