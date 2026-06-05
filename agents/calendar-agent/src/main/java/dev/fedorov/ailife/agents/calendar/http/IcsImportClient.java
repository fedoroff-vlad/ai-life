package dev.fedorov.ailife.agents.calendar.http;

import dev.fedorov.ailife.contracts.calendar.PullCalendarResult;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Drives mcp-ics-import's non-MCP {@code POST /internal/pull/{id}}. Used by the
 * scheduler-driven {@code ics.pull} trigger handler — straight HTTP, no LLM in
 * the loop.
 */
@Component
public class IcsImportClient {

    private final WebClient http;

    public IcsImportClient(WebClient icsImportWebClient) {
        this.http = icsImportWebClient;
    }

    public Mono<PullCalendarResult> pull(UUID subscriptionId) {
        return http.post()
                .uri("/internal/pull/{id}", subscriptionId)
                .retrieve()
                .bodyToMono(PullCalendarResult.class);
    }
}
