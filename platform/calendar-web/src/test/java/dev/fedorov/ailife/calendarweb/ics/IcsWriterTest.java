package dev.fedorov.ailife.calendarweb.ics;

import dev.fedorov.ailife.contracts.calendar.CalendarEventDto;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IcsWriterTest {

    @Test
    void rendersWellFormedVcalendarWithEvent() {
        var event = new CalendarEventDto(
                UUID.randomUUID(), UUID.randomUUID(), "ours", "uid-123",
                "Dentist, 9am", "Bring; insurance card", "Clinic",
                Instant.parse("2026-07-01T09:00:00Z"), Instant.parse("2026-07-01T10:00:00Z"),
                "FREQ=YEARLY", List.of("health"), null);

        String ics = IcsWriter.render("Vlad", List.of(event));

        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\n");
        assertThat(ics).endsWith("END:VCALENDAR\r\n");
        assertThat(ics)
                .contains("VERSION:2.0")
                .contains("X-WR-CALNAME:Vlad")
                .contains("BEGIN:VEVENT")
                .contains("UID:uid-123")
                .contains("DTSTART:20260701T090000Z")
                .contains("DTEND:20260701T100000Z")
                .contains("RRULE:FREQ=YEARLY")
                .contains("LOCATION:Clinic")
                .contains("CATEGORIES:health")
                .contains("END:VEVENT");
        // RFC-5545 escaping: comma + semicolon in text values are backslash-escaped.
        assertThat(ics).contains("SUMMARY:Dentist\\, 9am");
        assertThat(ics).contains("DESCRIPTION:Bring\\; insurance card");
        // Every line ends with CRLF.
        assertThat(ics.lines().count()).isGreaterThan(8);
    }

    @Test
    void emptyEventsStillRenderACalendarShell() {
        String ics = IcsWriter.render("Family", List.of());
        assertThat(ics).contains("BEGIN:VCALENDAR").contains("END:VCALENDAR").doesNotContain("BEGIN:VEVENT");
    }

    @Test
    void foldsLongLinesAtSeventyFiveOctets() {
        StringBuilder sb = new StringBuilder();
        IcsWriter.line(sb, "SUMMARY:" + "x".repeat(200));
        String folded = sb.toString();
        // No raw (unfolded) line exceeds 75 octets; continuation lines start with a space.
        for (String l : folded.split("\r\n")) {
            assertThat(l.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
        }
        assertThat(folded).contains("\r\n ");
        // Unfolding (strip CRLF+space) restores the original content.
        assertThat(folded.replace("\r\n ", "")).isEqualTo("SUMMARY:" + "x".repeat(200) + "\r\n");
    }
}
