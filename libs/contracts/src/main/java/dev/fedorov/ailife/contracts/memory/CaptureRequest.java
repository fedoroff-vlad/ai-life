package dev.fedorov.ailife.contracts.memory;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Ask memory-service to learn from a piece of dialogue (Stage 4 / memory-from-chat):
 * extract durable facts about the speaker or the people they mention and persist
 * them as memories. {@code householdId} + {@code text} are required; {@code userId}
 * scopes self-facts to the speaker; {@code personId} (optional) hints that the
 * message is about a specific person so the stored memory is person-scoped.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaptureRequest(
        UUID householdId,
        UUID userId,
        UUID personId,
        String text) {
}
