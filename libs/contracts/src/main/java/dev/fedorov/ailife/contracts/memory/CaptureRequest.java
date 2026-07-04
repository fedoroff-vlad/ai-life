package dev.fedorov.ailife.contracts.memory;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Ask memory-service to learn from a piece of dialogue (Stage 4 / memory-from-chat):
 * extract durable facts about the speaker or the people they mention and persist
 * them as memories. {@code householdId} + {@code text} are required; {@code userId}
 * scopes self-facts to the speaker; {@code personId} (optional) hints that the
 * message is about a specific person so the stored memory is person-scoped.
 *
 * <p>{@code channel} is the source channel the message arrived on (e.g. {@code "telegram"},
 * carried by {@code MessageReceivedEvent.source}). It is the third key of a conversation
 * route-lock, so ambient capture needs it to raise an approval question for an important
 * inferred fact (AC-4). Absent (a direct/sync {@code /v1/capture} caller) → no ambient
 * approval push; the rest of capture is unaffected.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaptureRequest(
        UUID householdId,
        UUID userId,
        UUID personId,
        String text,
        String channel) {

    /** Back-compat for callers with no channel (direct {@code /v1/capture}, older producers). */
    public CaptureRequest(UUID householdId, UUID userId, UUID personId, String text) {
        this(householdId, userId, personId, text, null);
    }
}
