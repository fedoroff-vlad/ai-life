package dev.fedorov.ailife.contracts.conversation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Active short-term control state for one (household, user, channel) conversation.
 * {@code routeLock} names the agent that owns an open question; {@code pendingAction}
 * is the opaque payload that agent resumes on the user's reply. {@code expiresAt} is
 * the TTL after which the lock is stale and ignored.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversationStateDto(
        UUID id,
        UUID householdId,
        UUID userId,
        String channel,
        String routeLock,
        JsonNode pendingAction,
        Instant expiresAt,
        Instant updatedAt) {
}
