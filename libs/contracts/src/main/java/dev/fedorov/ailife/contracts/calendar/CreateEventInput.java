package dev.fedorov.ailife.contracts.calendar;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateEventInput(
        UUID householdId,
        String summary,
        String description,
        String location,
        Instant dtstart,
        Instant dtend,
        String rrule,
        List<String> categories,
        UUID personId) {
}
