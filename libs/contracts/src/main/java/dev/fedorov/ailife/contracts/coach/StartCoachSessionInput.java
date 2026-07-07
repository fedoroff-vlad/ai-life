package dev.fedorov.ailife.contracts.coach;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/** Open a coaching session for a subject. {@code mode} ∈ reflect|develop|intake. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StartCoachSessionInput(
        UUID householdId,
        UUID subject,
        String mode,
        String summary) {
}
