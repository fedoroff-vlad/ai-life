package dev.fedorov.ailife.contracts.coach;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * A coaching session envelope — observations/actions hang off it for continuity ("last time
 * you said…"). {@code mode} ∈ reflect|develop|intake.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CoachSessionDto(
        UUID id,
        UUID householdId,
        UUID subject,
        String mode,
        String summary,
        Instant createdAt) {
}
