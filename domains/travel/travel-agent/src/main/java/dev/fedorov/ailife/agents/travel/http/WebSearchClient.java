package dev.fedorov.ailife.agents.travel.http;

import dev.fedorov.ailife.contracts.web.WebSearchInput;
import dev.fedorov.ailife.contracts.web.WebSearchResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Calls the shared {@code mcp-web} capability's {@code POST /internal/search} passthrough to gather
 * <b>qualitative destination research</b> — destination ideas, season reviews, deal roundups — for the
 * TR-d trip plan. This is <b>information</b> (articles, recommendations), never live pricing/availability
 * ([ADR-0003](../../../../plans/adr/ADR-0003-travel-data-source.md)); the composer cites these links as
 * provenance and must not present any figure in them as a bookable price. Soft-fails to an empty Mono so
 * a search hiccup drops only the research step. Mirrors briefing-agent's {@code NewsSearchClient} against
 * the same capability.
 */
@Component
public class WebSearchClient {

    private final WebClient http;

    public WebSearchClient(@Qualifier("mcpWebWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<WebSearchResult> search(String query, int limit) {
        return http.post()
                .uri("/internal/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WebSearchInput(query, limit))
                .retrieve()
                .bodyToMono(WebSearchResult.class)
                .timeout(Duration.ofSeconds(15))
                .onErrorResume(e -> Mono.empty());
    }
}
