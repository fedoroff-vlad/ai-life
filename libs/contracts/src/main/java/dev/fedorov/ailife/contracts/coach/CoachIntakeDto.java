package dev.fedorov.ailife.contracts.coach;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored answer to a deliberate intake question. {@code askedBy} ∈ onboarding|session.
 * coach_value / coach_profile are derived/seeded from these (CO-3).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CoachIntakeDto(
        UUID id,
        UUID householdId,
        UUID subject,
        String topic,
        String question,
        String answer,
        String askedBy,
        Instant createdAt) {
}
