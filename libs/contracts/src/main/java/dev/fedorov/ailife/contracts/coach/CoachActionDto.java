package dev.fedorov.ailife.contracts.coach;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * A proposed next step (Develop-mode follow-up spine). Optionally tied to a value and/or a
 * hypothesis. {@code status} ∈ proposed|active|done|dropped; {@code dueAt} optional.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CoachActionDto(
        UUID id,
        UUID householdId,
        UUID subject,
        String text,
        UUID valueId,
        UUID hypothesisId,
        String status,
        Instant dueAt,
        Instant createdAt,
        Instant updatedAt) {
}
