package dev.fedorov.ailife.memory.http;

import dev.fedorov.ailife.contracts.notify.NotifyRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.UUID;

/**
 * Thin notifier-service client for ambient capture (AC-4): push the "заметил: … — записать?" approval
 * question to the owner ({@code POST /v1/notify}). Best-effort — a failure just means the owner isn't
 * asked, so the inferred fact is dropped rather than saved; capture never fails on it.
 */
@Component
public class NotifierClient {

    private static final Logger log = LoggerFactory.getLogger(NotifierClient.class);

    private final WebClient http;

    public NotifierClient(@Qualifier("notifierWebClient") WebClient http) {
        this.http = http;
    }

    /** Send {@code text} to {@code userId}. Returns whether it was delivered (best-effort). */
    public boolean notify(UUID userId, String text) {
        if (userId == null || text == null || text.isBlank()) {
            return false;
        }
        try {
            http.post()
                    .uri("/v1/notify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new NotifyRequest(userId, text))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            return true;
        } catch (Exception e) {
            log.warn("ambient-approval notify failed for user={}: {}", userId, e.toString());
            return false;
        }
    }
}
