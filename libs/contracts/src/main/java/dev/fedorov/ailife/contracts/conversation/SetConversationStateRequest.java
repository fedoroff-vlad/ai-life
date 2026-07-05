package dev.fedorov.ailife.contracts.conversation;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Upsert the control state for one (household, user, channel) conversation: lock the
 * dialog to {@code routeLock} (the agent awaiting a reply) with an opaque
 * {@code pendingAction} to resume, alive for {@code ttlSeconds}. Replaces any existing
 * row for the same key. {@code ttlSeconds} null → the service applies a default.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SetConversationStateRequest(
        UUID householdId,
        UUID userId,
        String channel,
        String routeLock,
        JsonNode pendingAction,
        Long ttlSeconds) {
}
