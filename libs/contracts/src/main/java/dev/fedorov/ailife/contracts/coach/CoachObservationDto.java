package dev.fedorov.ailife.contracts.coach;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * One grounded observation from a session. {@code method} ∈ cbt|act|mi|sfbt|ifs (which move
 * produced it); {@code evidenceRefs} is a free-form JSON array of note/brief ids it rests on.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CoachObservationDto(
        UUID id,
        UUID householdId,
        UUID subject,
        UUID sessionId,
        String text,
        String method,
        JsonNode evidenceRefs,
        Instant createdAt) {
}
