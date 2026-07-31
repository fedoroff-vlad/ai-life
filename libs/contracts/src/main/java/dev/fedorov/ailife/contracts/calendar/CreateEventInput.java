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
        UUID personId,
        SharingScope sharing) {

    /**
     * Copy with a different {@code householdId} — used by the calendar-agent to route the item to the
     * concrete personal/family household its {@link #sharing} choice resolves to (ADR-0001 slice 4)
     * before handing it to mcp-caldav, which stays tenant-agnostic.
     */
    public CreateEventInput withHouseholdId(UUID resolvedHouseholdId) {
        return new CreateEventInput(resolvedHouseholdId, summary, description, location,
                dtstart, dtend, rrule, categories, personId, sharing);
    }
}
