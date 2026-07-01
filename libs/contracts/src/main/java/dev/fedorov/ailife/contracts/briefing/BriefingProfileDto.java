package dev.fedorov.ailife.contracts.briefing;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * A person's briefing preferences — one per (household, owner). Mirrors a
 * {@code briefing.briefing_profile} row; a null {@code ownerId} is the household-default. It drives
 * the proactive morning digest: {@code locationLabel}/{@code latitude}/{@code longitude}/
 * {@code timezone} are the weather + schedule location (the briefing-profiler geocodes a stated city
 * into lat/lon); {@code interests} is a free-form JSON array of news topics; {@code sections} is a
 * JSON array of enabled section keys ({@code weather|agenda|finance|news}); {@code scheduleTime}
 * ({@code "HH:mm"}) + {@code scheduleEnabled} drive the per-person wake (BR-f). Absent fields stay null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BriefingProfileDto(
        UUID id,
        UUID householdId,
        UUID ownerId,
        String locationLabel,
        Double latitude,
        Double longitude,
        String timezone,
        JsonNode interests,
        JsonNode sections,
        String scheduleTime,
        Boolean scheduleEnabled,
        String notes,
        Instant updatedAt) {
}
