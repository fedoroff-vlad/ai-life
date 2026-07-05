package dev.fedorov.ailife.contracts.briefing;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Upsert a person's briefing preferences. Keyed on (householdId, ownerId) — a null ownerId is the
 * household-default. {@code householdId} is required; every other field is applied as given (a full
 * set, not a partial merge — the briefing-profiler flow recomputes the whole profile from the typed
 * config message). {@code interests} (news topics) and {@code sections} (enabled section keys) are
 * free-form JSON arrays; {@code latitude}/{@code longitude} are the geocoded coordinates of
 * {@code locationLabel}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SetBriefingProfileInput(
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
        String notes) {
}
