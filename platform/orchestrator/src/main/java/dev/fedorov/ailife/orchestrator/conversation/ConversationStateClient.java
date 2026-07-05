package dev.fedorov.ailife.orchestrator.conversation;

import dev.fedorov.ailife.contracts.conversation.ConversationStateDto;
import dev.fedorov.ailife.contracts.conversation.SetConversationStateRequest;
import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Reactive client over conversation-service ({@code /v1/conversation-state}).
 *
 * <p>Strict no-throw contract (same posture as {@code MemoryClient}): disabled, any missing key,
 * 204 (no active lock), network error or timeout all collapse to empty/complete. Conversation state
 * is an enhancement around routing — we never block routing on it, so a failure simply means
 * "classify normally" (read) or "the confirmation won't survive" (write), never a user-facing error.
 */
@Component
public class ConversationStateClient {

    private static final Logger log = LoggerFactory.getLogger(ConversationStateClient.class);
    private static final Duration TIMEOUT = Duration.ofMillis(500);

    private final WebClient http;
    private final ConversationProperties props;

    public ConversationStateClient(WebClient.Builder builder, ConversationProperties props) {
        this.http = builder.clone().baseUrl(props.getUrl()).build();
        this.props = props;
    }

    /** Active control state for this conversation, or empty when there is none (or on any failure). */
    public Mono<ConversationStateDto> activeState(UUID householdId, UUID userId, String channel) {
        if (!props.isEnabled() || householdId == null || userId == null
                || channel == null || channel.isBlank()) {
            return Mono.empty();
        }
        return http.get()
                .uri(uri -> uri.path("/v1/conversation-state")
                        .queryParam("householdId", householdId)
                        .queryParam("userId", userId)
                        .queryParam("channel", channel)
                        .build())
                .retrieve()
                .bodyToMono(ConversationStateDto.class)   // 204 → empty Mono
                .timeout(TIMEOUT)
                .onErrorResume(e -> {
                    log.warn("conversation-state lookup failed for household={} user={}: {}",
                            householdId, userId, e.toString());
                    return Mono.empty();
                });
    }

    /** Lock the conversation to {@code routeLock} with a pending action. Soft-fail → completes empty. */
    public Mono<Void> lock(UUID householdId, UUID userId, String channel,
                           String routeLock, JsonNode pendingAction) {
        if (!props.isEnabled() || householdId == null || userId == null
                || channel == null || channel.isBlank()) {
            return Mono.empty();
        }
        var req = new SetConversationStateRequest(
                householdId, userId, channel, routeLock, pendingAction, null);
        return http.put()
                .uri("/v1/conversation-state")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .retrieve()
                .toBodilessEntity()
                .timeout(TIMEOUT)
                .then()
                .onErrorResume(e -> {
                    log.warn("conversation-state lock failed for household={} user={}: {}",
                            householdId, userId, e.toString());
                    return Mono.empty();
                });
    }

    /** Clear any lock for this conversation (resolved). Soft-fail → completes empty. */
    public Mono<Void> clear(UUID householdId, UUID userId, String channel) {
        if (!props.isEnabled() || householdId == null || userId == null
                || channel == null || channel.isBlank()) {
            return Mono.empty();
        }
        return http.delete()
                .uri(uri -> uri.path("/v1/conversation-state")
                        .queryParam("householdId", householdId)
                        .queryParam("userId", userId)
                        .queryParam("channel", channel)
                        .build())
                .retrieve()
                .toBodilessEntity()
                .timeout(TIMEOUT)
                .then()
                .onErrorResume(e -> {
                    log.warn("conversation-state clear failed for household={} user={}: {}",
                            householdId, userId, e.toString());
                    return Mono.empty();
                });
    }
}
