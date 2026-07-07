package dev.fedorov.ailife.contracts.coach;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Persist an observation. {@code method} ∈ cbt|act|mi|sfbt|ifs; {@code sessionId} links it to
 * its session (must belong to the same subject); {@code evidenceRefs} is an optional JSON array.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AddCoachObservationInput(
        UUID householdId,
        UUID subject,
        UUID sessionId,
        String text,
        String method,
        JsonNode evidenceRefs) {
}
