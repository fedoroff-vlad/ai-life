package dev.fedorov.ailife.mcp.moneyproimport.importer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Best-effort Money Pro date parser. Tries date-time formats first, then date-only
 * formats — Money Pro typically exports {@code dd.MM.yyyy HH:mm:ss} (RU) or
 * {@code yyyy-MM-dd} / {@code MM/dd/yyyy} (EN). Date-only values land at midnight
 * UTC; full timestamps are taken at UTC. We don't currently honour an export's
 * own timezone — Money Pro CSV doesn't preserve it. Good enough for history import.
 */
final class DateParser {

    private DateParser() {
    }

    private static final List<DateTimeFormatter> DATE_TIME = List.of(
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm"));

    private static final List<DateTimeFormatter> DATE_ONLY = List.of(
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"));

    static Instant parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("date is null");
        String s = raw.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("date is blank");
        for (DateTimeFormatter f : DATE_TIME) {
            try {
                return LocalDateTime.parse(s, f).toInstant(ZoneOffset.UTC);
            } catch (Exception ignored) {
            }
        }
        for (DateTimeFormatter f : DATE_ONLY) {
            try {
                return LocalDate.parse(s, f).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (Exception ignored) {
            }
        }
        throw new IllegalArgumentException("unrecognised date format: " + raw);
    }
}
