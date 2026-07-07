package dev.fedorov.ailife.contracts.coach;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * A candidate recurring pattern — explicitly a hypothesis, revised as new data arrives.
 * {@code status} ∈ open|supported|revised|dropped; {@code confidence} is optional (0–100);
 * supporting/contradicting observation-id arrays are free-form JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CoachHypothesisDto(
        UUID id,
        UUID householdId,
        UUID subject,
        String text,
        String status,
        Integer confidence,
        JsonNode supportingObservationIds,
        JsonNode contradictingObservationIds,
        Instant createdAt,
        Instant updatedAt) {
}
