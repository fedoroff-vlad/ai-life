package dev.fedorov.ailife.agentruntime.http;

import dev.fedorov.ailife.contracts.web.WebSearchInput;
import dev.fedorov.ailife.contracts.web.WebSearchResult;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Shared client for the {@code mcp-web} capability's {@code POST /internal/search} passthrough: the
 * deterministic, MockWebServer-testable retrieval path (MCP/SSE can't be MockWebServer'd). Cheap — no
 * model cost. Returns the raw {@link WebSearchResult}; the caller maps/filters its hits.
 *
 * <p>Lives in {@code agent-runtime} because almost every reasoning agent searches the web (research,
 * recipes, store lookups, trend/news gathers, destination research): a capability HTTP client is shared
 * code, not a per-agent copy. It replaced six near-identical per-agent copies (researcher/chef/
 * nutritionist/stylist/travel {@code WebSearchClient}, briefing {@code NewsSearchClient}) and creator's
 * {@code TrendGatherClient} web leg. Opt-in — a consuming agent declares the {@code @Bean} in its
 * {@code OutboundHttpConfig}, passing a {@code WebClient} pointed at {@code mcp-web}.
 *
 * <p>Default timeout is 15s; the {@link #search(String, Integer, Duration)} overload lets a latency-
 * sensitive caller (the briefing digest) tighten it. Errors and empty bodies propagate — callers apply
 * their own {@code onErrorResume} / {@code defaultIfEmpty} per their soft-fail posture.
 */
public class WebSearchClient {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    private final WebClient http;

    public WebSearchClient(WebClient http) {
        this.http = http;
    }

    public Mono<WebSearchResult> search(String query, Integer limit) {
        return search(query, limit, DEFAULT_TIMEOUT);
    }

    public Mono<WebSearchResult> search(String query, Integer limit, Duration timeout) {
        return http.post()
                .uri("/internal/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WebSearchInput(query, limit))
                .retrieve()
                .bodyToMono(WebSearchResult.class)
                .timeout(timeout);
    }
}
