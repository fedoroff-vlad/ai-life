package dev.fedorov.ailife.calendarweb.ics;

import dev.fedorov.ailife.contracts.calendar.CalendarEventDto;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders a read-only {@code text/calendar} (ICS / RFC 5545) document from {@link CalendarEventDto}s —
 * the feed a subscribed Apple / Google / Yandex calendar polls. Hand-rolled (not ical4j) on purpose: the
 * output is small and read-only, so a tiny, fully-tested writer keeps the service dependency-light. It
 * handles the three things that actually bite: text escaping, CRLF line endings, and 75-octet folding.
 */
public final class IcsWriter {

    private static final String CRLF = "\r\n";
    /** UTC date-time form, e.g. {@code 20260701T090000Z}. */
    private static final DateTimeFormatter UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private IcsWriter() {
    }

    /** A full VCALENDAR with one VEVENT per event; {@code calendarName} becomes the display name. */
    public static String render(String calendarName, List<CalendarEventDto> events) {
        StringBuilder sb = new StringBuilder();
        line(sb, "BEGIN:VCALENDAR");
        line(sb, "VERSION:2.0");
        line(sb, "PRODID:-//ai-life//calendar-web//EN");
        line(sb, "CALSCALE:GREGORIAN");
        line(sb, "METHOD:PUBLISH");
        line(sb, "X-WR-CALNAME:" + escape(calendarName == null ? "ai-life" : calendarName));
        String stamp = UTC.format(Instant.now());
        for (CalendarEventDto e : events) {
            line(sb, "BEGIN:VEVENT");
            line(sb, "UID:" + escape(uid(e)));
            line(sb, "DTSTAMP:" + stamp);
            if (e.dtstart() != null) {
                line(sb, "DTSTART:" + UTC.format(e.dtstart()));
            }
            if (e.dtend() != null) {
                line(sb, "DTEND:" + UTC.format(e.dtend()));
            }
            line(sb, "SUMMARY:" + escape(e.summary() == null ? "(no title)" : e.summary()));
            if (notBlank(e.description())) {
                line(sb, "DESCRIPTION:" + escape(e.description()));
            }
            if (notBlank(e.location())) {
                line(sb, "LOCATION:" + escape(e.location()));
            }
            if (notBlank(e.rrule())) {
                line(sb, "RRULE:" + e.rrule().trim());
            }
            if (e.categories() != null && !e.categories().isEmpty()) {
                line(sb, "CATEGORIES:" + String.join(",", e.categories().stream().map(IcsWriter::escape).toList()));
            }
            line(sb, "END:VEVENT");
        }
        line(sb, "END:VCALENDAR");
        return sb.toString();
    }

    private static String uid(CalendarEventDto e) {
        if (notBlank(e.calendarUid())) {
            return e.calendarUid();
        }
        return (e.id() != null ? e.id().toString() : "unknown") + "@ai-life";
    }

    /** Escape a property value per RFC 5545 §3.3.11 (backslash, newline, comma, semicolon). */
    static String escape(String v) {
        if (v == null) {
            return "";
        }
        return v.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace(",", "\\,")
                .replace(";", "\\;");
    }

    /** Append a content line, folded to ≤75 octets per RFC 5545 §3.1, terminated by CRLF. */
    static void line(StringBuilder sb, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 75) {
            sb.append(content).append(CRLF);
            return;
        }
        int count = 0;
        boolean first = true;
        StringBuilder chunk = new StringBuilder();
        for (int i = 0; i < content.length(); ) {
            int cp = content.codePointAt(i);
            int w = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            // A folded continuation line begins with a space, so it carries 1 fewer octet of payload.
            int limit = first ? 75 : 74;
            if (count + w > limit) {
                sb.append(first ? "" : " ").append(chunk).append(CRLF);
                chunk.setLength(0);
                count = 0;
                first = false;
            }
            chunk.appendCodePoint(cp);
            count += w;
            i += Character.charCount(cp);
        }
        sb.append(first ? "" : " ").append(chunk).append(CRLF);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
