package dev.fedorov.ailife.contracts.conversation;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Upsert the control state for one (household, user, channel) conversation: lock the
 * dialog to {@code routeLock} (the agent awaiting a reply) with an opaque
 * {@code pendingAction} to resume, and/or record {@code lastRouteAgent} /
 * {@code lastRouteText} (the last fresh routing, for misroute-repair #484) plus an
 * optional {@code lastRouteTrace} (a short, payload-free line of what that agent read/wrote,
 * for the "why did you do that" trace #485 / Track G), and/or record the last *mutating* action
 * ({@code lastMutationAgent} / {@code lastMutationPayload} / {@code lastMutationDesc}, for the
 * "отмени последнее / undo" primitive #486 / Track H), alive for {@code ttlSeconds}. Replaces
 * any existing row for the same key — a field left null clears it (so a caller preserving one
 * group across a write of another must carry the untouched fields forward). {@code ttlSeconds}
 * null → the service applies a default.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SetConversationStateRequest(
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
        Long ttlSeconds) {

    /** Back-compat for callers predating the undo primitive (#486) — no last-mutation. */
    public SetConversationStateRequest(UUID householdId, UUID userId, String channel,
                                       String routeLock, JsonNode pendingAction,
                                       String lastRouteAgent, String lastRouteText, String lastRouteTrace,
                                       Long ttlSeconds) {
        this(householdId, userId, channel, routeLock, pendingAction,
                lastRouteAgent, lastRouteText, lastRouteTrace, null, null, null, ttlSeconds);
    }
}
