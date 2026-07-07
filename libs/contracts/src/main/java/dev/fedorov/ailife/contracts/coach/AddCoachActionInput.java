package dev.fedorov.ailife.contracts.coach;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/** Propose a next step (lands as status=proposed). {@code valueId}/{@code hypothesisId}/{@code dueAt} optional. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AddCoachActionInput(
        UUID householdId,
        UUID subject,
        String text,
        UUID valueId,
        UUID hypothesisId,
        Instant dueAt) {
}
