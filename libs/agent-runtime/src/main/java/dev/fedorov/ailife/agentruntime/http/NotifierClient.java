package dev.fedorov.ailife.agentruntime.http;

import dev.fedorov.ailife.contracts.notify.NotifyRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Shared notifier-service client. Per-user delivery failures are the caller's
 * problem — {@code TriggerController}s log + swallow them so one bad user
 * doesn't block the household.
 *
 * <p>Every send through this client is a <b>proactive</b> push — a scheduler/bus wake or an async ack the
 * user did not just ask for (a reactive reply travels the orchestrator → gateway path, never notifier). So
 * sends are marked {@code proactive=true} and are subject to the owner's proactive-UX gate (quiet hours /
 * caps, #487). Use {@link #notify(UUID, String, boolean)} for the rare send that must bypass the gate.
 */
public class NotifierClient {

    private final WebClient http;

    public NotifierClient(@Qualifier("notifierWebClient") WebClient http) {
        this.http = http;
    }

    /** Proactive push (gate-able under #487). See the class note. */
    public Mono<Void> notify(UUID userId, String text) {
        return notify(userId, text, true);
    }

    public Mono<Void> notify(UUID userId, String text, boolean proactive) {
        return http.post()
                .uri("/v1/notify")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new NotifyRequest(userId, text, proactive))
                .retrieve()
                .toBodilessEntity()
                .then();
    }
}
