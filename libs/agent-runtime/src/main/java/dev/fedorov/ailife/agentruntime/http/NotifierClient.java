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
 */
public class NotifierClient {

    private final WebClient http;

    public NotifierClient(@Qualifier("notifierWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<Void> notify(UUID userId, String text) {
        return http.post()
                .uri("/v1/notify")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new NotifyRequest(userId, text))
                .retrieve()
                .toBodilessEntity()
                .then();
    }
}
