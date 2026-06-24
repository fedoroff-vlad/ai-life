package dev.fedorov.ailife.agents.creator.http;

import dev.fedorov.ailife.contracts.trends.FeedItemsInput;
import dev.fedorov.ailife.contracts.trends.RedditTrendsInput;
import dev.fedorov.ailife.contracts.trends.TrendHit;
import dev.fedorov.ailife.contracts.trends.YoutubeTrendsInput;
import dev.fedorov.ailife.contracts.web.WebSearchInput;
import dev.fedorov.ailife.contracts.web.WebSearchResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * The creator's cheap-first trend gather (CR-d): one call per source capability over its
 * MockWebServer-testable {@code /internal/*} passthrough (MCP/SSE can't be mocked), each returning the
 * uniform {@link TrendHit} the Coordinator folds into one corpus. All four sources are bound here —
 * {@code mcp-web} (search), {@code mcp-youtube}, {@code mcp-reddit}, {@code mcp-feeds}. No model cost:
 * gathering is plain HTTP; the single LLM call is the synthesis. Each method is wired to soft-fail at
 * the call site (the Coordinator omits a source that errors or returns nothing).
 */
@Component
public class TrendGatherClient {

    private static final ParameterizedTypeReference<List<TrendHit>> HITS = new ParameterizedTypeReference<>() {};
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WebClient web;
    private final WebClient youtube;
    private final WebClient reddit;
    private final WebClient feeds;

    public TrendGatherClient(@Qualifier("mcpWebWebClient") WebClient web,
                             @Qualifier("mcpYoutubeWebClient") WebClient youtube,
                             @Qualifier("mcpRedditWebClient") WebClient reddit,
                             @Qualifier("mcpFeedsWebClient") WebClient feeds) {
        this.web = web;
        this.youtube = youtube;
        this.reddit = reddit;
        this.feeds = feeds;
    }

    /** Web search via {@code mcp-web}; its {@code WebSearchHit}s are mapped to the uniform {@link TrendHit}. */
    public Mono<List<TrendHit>> web(String query, int limit) {
        return web.post().uri("/internal/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WebSearchInput(query, limit))
                .retrieve()
                .bodyToMono(WebSearchResult.class)
                .timeout(TIMEOUT)
                .map(r -> r.hits() == null ? List.<TrendHit>of()
                        : r.hits().stream()
                        .map(h -> new TrendHit("web", "web", h.title(), h.url(), h.snippet(), null))
                        .toList());
    }

    public Mono<List<TrendHit>> youtube(String query, int maxResults) {
        return youtube.post().uri("/internal/youtube-trends")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new YoutubeTrendsInput(query, maxResults))
                .retrieve()
                .bodyToMono(HITS)
                .timeout(TIMEOUT);
    }

    public Mono<List<TrendHit>> reddit(String query, int maxResults) {
        return reddit.post().uri("/internal/reddit-trends")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new RedditTrendsInput(null, query, maxResults))
                .retrieve()
                .bodyToMono(HITS)
                .timeout(TIMEOUT);
    }

    /** Feed items from a specific RSS URL or public Telegram channel handle named in the request. */
    public Mono<List<TrendHit>> feeds(String source, int maxResults) {
        return feeds.post().uri("/internal/feed-items")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new FeedItemsInput(source, maxResults))
                .retrieve()
                .bodyToMono(HITS)
                .timeout(TIMEOUT);
    }
}
