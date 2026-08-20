package dev.fedorov.ailife.contracts.conversation;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Active short-term control state for one (household, user, channel) conversation.
 * {@code routeLock} names the agent that owns an open question; {@code pendingAction}
 * is the opaque payload that agent resumes on the user's reply. {@code expiresAt} is
 * the TTL after which the lock is stale and ignored.
 *
 * <p>{@code lastRouteAgent} / {@code lastRouteText} back misroute-repair (road-test #484):
 * the agent a fresh message was last routed to and that message's text, so a correction on
 * the next turn re-classifies with the prior route as context. Both are null when the last
 * turn set no route (or opened a question, in which case {@code routeLock} takes priority).
 * {@code lastRouteTrace} is an optional short, payload-free line of what that agent read/wrote,
 * folded into the "why did you do that" answer (#485 / Track G); null when the agent contributed none.
 *
 * <p>{@code lastMutationAgent} / {@code lastMutationPayload} / {@code lastMutationDesc} back the
 * "отмени последнее / undo" primitive (road-test #486, Track H): the agent that performed the last
 * *mutating* write, the opaque handle it reverses with ({@code payload} — internal ids/inverse-op,
 * never surfaced), and a short user-facing description. Unlike last-route, this is written only by a
 * terminal write and cleared when undone; all null when the conversation has no undoable mutation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversationStateDto(
        UUID id,
        UUID householdId,
        UUID userId,
        String channel,
        String routeLock,
        JsonNode pendingAction,
        String lastRouteAgent,
        String lastRouteText,
        String lastRouteTrace,
        String lastMutationAgent,
        JsonNode lastMutationPayload,
        String lastMutationDesc,
        Instant expiresAt,
        Instant updatedAt) {

    /** Back-compat for callers/tests predating the undo primitive (#486) — no last-mutation. */
    public ConversationStateDto(UUID id, UUID householdId, UUID userId, String channel,
                                String routeLock, JsonNode pendingAction,
                                String lastRouteAgent, String lastRouteText, String lastRouteTrace,
                                Instant expiresAt, Instant updatedAt) {
        this(id, householdId, userId, channel, routeLock, pendingAction,
                lastRouteAgent, lastRouteText, lastRouteTrace, null, null, null, expiresAt, updatedAt);
    }
}
