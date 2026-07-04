package dev.fedorov.ailife.memory.http;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fedorov.ailife.contracts.conversation.SetConversationStateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.UUID;

/**
 * Thin conversation-service write client for ambient capture (AC-4). When memory-service notices an
 * important inferred fact it route-locks the conversation to an agent with an opaque {@code pendingAction}
 * so the owner's reply resumes there ("заметил: … — записать?"). Best-effort — any failure (service down,
 * missing key) is swallowed: a confirmation that can't be persisted just won't happen, never an error on
 * the capture path.
 */
@Component
public class ConversationStateClient {

    private static final Logger log = LoggerFactory.getLogger(ConversationStateClient.class);

    private final WebClient http;

    public ConversationStateClient(@Qualifier("conversationWebClient") WebClient http) {
        this.http = http;
    }

    /**
     * Lock this (household, user, channel) conversation to {@code routeLock} with a pending action.
     * Returns whether the lock was persisted — the caller only asks the question if we can remember it.
     */
    public boolean lock(UUID householdId, UUID userId, String channel, String routeLock, JsonNode pendingAction) {
        if (householdId == null || userId == null || channel == null || channel.isBlank()) {
            return false;
        }
        try {
            http.put()
                    .uri("/v1/conversation-state")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new SetConversationStateRequest(
                            householdId, userId, channel, routeLock, pendingAction, null))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            return true;
        } catch (Exception e) {
            log.warn("conversation-state lock failed for household={} user={}: {}",
                    householdId, userId, e.toString());
            return false;
        }
    }
}
