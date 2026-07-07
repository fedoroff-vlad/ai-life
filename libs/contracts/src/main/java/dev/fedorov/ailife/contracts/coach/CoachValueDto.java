package dev.fedorov.ailife.contracts.coach;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * One value the subject holds (label + optional note). {@code source} ∈ stated|inferred;
 * {@code weight} is an optional relative importance. Subject-owned, editable; seeded from the
 * subject's own conversation/intake, never invented.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CoachValueDto(
        UUID id,
        UUID householdId,
        UUID subject,
        String label,
        String note,
        String source,
        Integer weight,
        boolean active,
        Instant createdAt) {
}
