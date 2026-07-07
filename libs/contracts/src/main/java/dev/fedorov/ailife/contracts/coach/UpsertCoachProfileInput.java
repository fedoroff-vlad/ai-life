package dev.fedorov.ailife.contracts.coach;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Create-or-update the coaching vector for a subject. Keyed on (householdId, subject) — at most
 * one profile per person. Non-null fields are applied; {@code active} defaults to true when null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpsertCoachProfileInput(
        UUID householdId,
        UUID subject,
        JsonNode methodWeights,
        String tone,
        JsonNode focusAreas,
        JsonNode boundaries,
        Boolean active) {
}
