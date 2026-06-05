package dev.fedorov.ailife.agents.calendar.http;

import dev.fedorov.ailife.contracts.notify.NotifyRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
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
