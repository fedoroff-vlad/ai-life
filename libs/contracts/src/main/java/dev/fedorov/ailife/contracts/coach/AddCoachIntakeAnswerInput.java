package dev.fedorov.ailife.contracts.coach;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/** Store an intake question + answer. {@code askedBy} defaults to "session" when null. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AddCoachIntakeAnswerInput(
        UUID householdId,
        UUID subject,
        String topic,
        String question,
        String answer,
        String askedBy) {
}
