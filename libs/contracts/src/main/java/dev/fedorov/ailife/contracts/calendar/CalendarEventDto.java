package dev.fedorov.ailife.contracts.calendar;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Single event mirrored from Radicale into the calendar.events_cache table.
 * The shape downstream agents (calendar-agent, scheduler) see.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CalendarEventDto(
        UUID id,
        UUID householdId,
        String sourceCalendar,
        String calendarUid,
        String summary,
        String description,
        String location,
        Instant dtstart,
        Instant dtend,
        String rrule,
        List<String> categories,
        UUID personId) {

    public CalendarEventDto {
        categories = categories == null ? List.of() : List.copyOf(categories);
    }
}
