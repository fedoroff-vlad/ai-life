package dev.fedorov.ailife.mcp.youtube.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.trends.TrendHit;
import dev.fedorov.ailife.mcp.youtube.config.McpYoutubeProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Default {@link VideoTrendsSource}: trending / most-relevant videos from the <b>YouTube Data API
 * v3</b>. One call to {@code GET /youtube/v3/search?part=snippet&type=video&order=relevance} for the
 * query — the cheapest gather (no per-video statistics fan-out; MVP). Each item maps to a uniform
 * {@link TrendHit} (platform {@code youtube}, the {@code watch?v=} link, channel + publish date in
 * {@code metrics}). Selected by {@code youtube.source=youtubedata} (the default).
 *
 * <p>The free quota needs an API key; when {@code youtube.api-key} is blank this returns an empty
 * list rather than calling the API, so the agent's multi-source gather soft-fails this source
 * gracefully (e.g. in CI) instead of erroring. A genuine transport failure propagates on the
 * {@link Mono} (the caller's gather soft-fails per source).
 */
@Component
@ConditionalOnProperty(name = "youtube.source", havingValue = "youtubedata", matchIfMissing = true)
public class YoutubeDataApiSource implements VideoTrendsSource {

    private static final String WATCH_URL = "https://www.youtube.com/watch?v=";
    private static final int MAX_API_RESULTS = 50;

    private final WebClient http;
    private final McpYoutubeProperties props;

    public YoutubeDataApiSource(@Qualifier("youtubeWebClient") WebClient http, McpYoutubeProperties props) {
        this.http = http;
        this.props = props;
    }

    @Override
    public Mono<List<TrendHit>> trends(String query, Integer maxResults) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty() || props.getApiKey() == null || props.getApiKey().isBlank()) {
            return Mono.just(List.of());
        }
        int limit = Math.min(maxResults == null || maxResults <= 0 ? props.getMaxResults() : maxResults, MAX_API_RESULTS);
        return http.get()
                .uri(uri -> uri.path("/search")
                        .queryParam("part", "snippet")
                        .queryParam("type", "video")
                        .queryParam("order", "relevance")
                        .queryParam("q", q)
                        .queryParam("maxResults", limit)
                        .queryParam("key", props.getApiKey())
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(10))
                .map(YoutubeDataApiSource::parse);
    }

    /** Map a YouTube Data API search response (or null) to a uniform {@link TrendHit} list. */
    private static List<TrendHit> parse(JsonNode body) {
        List<TrendHit> hits = new ArrayList<>();
        JsonNode items = body == null ? null : body.get("items");
        if (items == null || !items.isArray()) {
            return hits;
        }
        for (JsonNode item : items) {
            String videoId = path(item, "id", "videoId");
            JsonNode snippet = item.get("snippet");
            String title = text(snippet, "title");
            if (videoId == null || title == null) {
                continue;
            }
            hits.add(new TrendHit(
                    "youtube",
                    "youtube",
                    title,
                    WATCH_URL + videoId,
                    text(snippet, "description"),
                    metrics(text(snippet, "channelTitle"), text(snippet, "publishedAt"))));
        }
        return hits;
    }

    private static JsonNode metrics(String channel, String publishedAt) {
        if (channel == null && publishedAt == null) {
            return null;
        }
        ObjectNode m = JsonNodeFactory.instance.objectNode();
        if (channel != null) {
            m.put("channel", channel);
        }
        if (publishedAt != null) {
            m.put("publishedAt", publishedAt);
        }
        return m;
    }

    private static String path(JsonNode node, String parent, String child) {
        if (node == null) {
            return null;
        }
        return text(node.get(parent), child);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
