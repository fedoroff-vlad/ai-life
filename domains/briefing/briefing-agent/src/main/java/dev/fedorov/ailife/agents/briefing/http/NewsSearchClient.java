package dev.fedorov.ailife.agents.briefing.http;

import dev.fedorov.ailife.contracts.web.WebSearchInput;
import dev.fedorov.ailife.contracts.web.WebSearchResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * Reads news headlines from the shared {@code mcp-web} capability's {@code POST /internal/search}
 * passthrough (the deterministic, MockWebServer-testable path researcher-agent's {@code WebSearchClient}
 * also uses). The digest's news gather step runs one search per profile interest — links + snippets
 * only (no full-page fetch: the briefing wants headlines, not articles). A short timeout keeps a slow
 * search from stalling the digest.
 */
@Component
public class NewsSearchClient {

    private final WebClient http;

    public NewsSearchClient(@Qualifier("mcpWebWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<WebSearchResult> search(String topic, int limit) {
        return http.post()
                .uri("/internal/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WebSearchInput(topic, limit))
                .retrieve()
                .bodyToMono(WebSearchResult.class)
                .timeout(Duration.ofSeconds(8))
                .defaultIfEmpty(new WebSearchResult(topic, List.of()));
    }
}
