package dev.fedorov.ailife.contracts.coach;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Advance an action's follow-up state. {@code id} required; {@code status}
 * (proposed|active|done|dropped) and {@code dueAt} applied only when non-null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpdateCoachActionInput(
        UUID id,
        String status,
        Instant dueAt) {
}
