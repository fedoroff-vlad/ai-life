package dev.fedorov.ailife.mcp.web.engine;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fedorov.ailife.contracts.web.WebSearchHit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Default {@link SearchEngine}: queries a self-hosted <b>SearXNG</b> meta-search instance over
 * its JSON API ({@code GET /search?q=&format=json}) and maps the {@code results[]} array to
 * {@link WebSearchHit}s ({@code title}, {@code url}, {@code content}→snippet). Free, no API key,
 * no quota, private (queries leave from our own host). Selected by
 * {@code mcp-web.search-engine=searxng} (the default).
 */
@Component
@ConditionalOnProperty(name = "mcp-web.search-engine", havingValue = "searxng", matchIfMissing = true)
public class SearxngSearchEngine implements SearchEngine {

    private final WebClient http;

    public SearxngSearchEngine(@Qualifier("searxngWebClient") WebClient http) {
        this.http = http;
    }

    @Override
    public Mono<List<WebSearchHit>> search(String query, int limit) {
        return http.get()
                .uri(uri -> uri.path("/search")
                        .queryParam("q", query)
                        .queryParam("format", "json")
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(10))
                .map(body -> parse(body, limit));
    }

    private static List<WebSearchHit> parse(JsonNode body, int limit) {
        List<WebSearchHit> hits = new ArrayList<>();
        JsonNode results = body == null ? null : body.get("results");
        if (results == null || !results.isArray()) {
            return hits;
        }
        for (JsonNode r : results) {
            if (hits.size() >= limit) {
                break;
            }
            String url = text(r, "url");
            if (url == null) {
                continue; // a hit without a URL is useless to the caller
            }
            hits.add(new WebSearchHit(text(r, "title"), url, text(r, "content")));
        }
        return hits;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
