package dev.fedorov.ailife.contracts.coach;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Revise a hypothesis as the record sharpens (CO-6 reconciliation). {@code id} required; every
 * other field is applied only when non-null. {@code status} ∈ open|supported|revised|dropped.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpdateCoachHypothesisInput(
        UUID id,
        String status,
        Integer confidence,
        JsonNode supportingObservationIds,
        JsonNode contradictingObservationIds) {
}
