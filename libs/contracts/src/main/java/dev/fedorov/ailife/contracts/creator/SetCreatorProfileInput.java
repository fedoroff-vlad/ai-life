package dev.fedorov.ailife.contracts.creator;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Upsert a person's creator track. Keyed on (householdId, ownerId) — a null ownerId is the
 * household-default. {@code householdId} is required; every other field is applied as given (a full
 * set, not a partial merge — the creator-profiler flow recomputes the whole profile). {@code
 * platforms} and {@code guardrails} are free-form JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SetCreatorProfileInput(
        UUID householdId,
        UUID ownerId,
        String niche,
        String audience,
        String tone,
        JsonNode platforms,
        String goals,
        JsonNode guardrails,
        String notes) {
}
