package dev.fedorov.ailife.contracts.coach;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/** Propose a hypothesis (lands as status=open). {@code confidence} + observation-id arrays optional. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AddCoachHypothesisInput(
        UUID householdId,
        UUID subject,
        String text,
        Integer confidence,
        JsonNode supportingObservationIds,
        JsonNode contradictingObservationIds) {
}
