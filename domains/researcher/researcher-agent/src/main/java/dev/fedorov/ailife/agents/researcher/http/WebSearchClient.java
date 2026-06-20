package dev.fedorov.ailife.agents.researcher.http;

import dev.fedorov.ailife.contracts.web.WebSearchInput;
import dev.fedorov.ailife.contracts.web.WebSearchResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Calls the shared {@code mcp-web} capability's {@code POST /internal/search} passthrough.
 * The deterministic, MockWebServer-testable path the research flow uses (MCP/SSE can't be
 * MockWebServer'd). Cheap retrieval — no model cost.
 */
@Component
public class WebSearchClient {

    private final WebClient http;

    public WebSearchClient(@Qualifier("mcpWebWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<WebSearchResult> search(String query, Integer limit) {
        return http.post()
                .uri("/internal/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WebSearchInput(query, limit))
                .retrieve()
                .bodyToMono(WebSearchResult.class)
                .timeout(Duration.ofSeconds(15));
    }
}
