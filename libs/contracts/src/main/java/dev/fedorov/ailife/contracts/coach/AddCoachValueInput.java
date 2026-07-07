package dev.fedorov.ailife.contracts.coach;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/** Record a value for a subject. {@code source} defaults to "stated" when null. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AddCoachValueInput(
        UUID householdId,
        UUID subject,
        String label,
        String note,
        String source,
        Integer weight) {
}
