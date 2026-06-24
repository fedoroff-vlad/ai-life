package dev.fedorov.ailife.mcp.reddit.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.trends.TrendHit;
import dev.fedorov.ailife.mcp.reddit.config.McpRedditProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Default {@link SocialTrendsSource}: hot / most-relevant posts from the <b>Reddit API</b>. App-only
 * OAuth — one {@code POST /api/v1/access_token} (grant {@code client_credentials}) mints a bearer
 * token, then one listing call ({@code /r/{sub}/hot}, {@code /r/{sub}/search}, or global
 * {@code /search}). Each post maps to a uniform {@link TrendHit} (platform {@code reddit}, the
 * permalink, subreddit + score + comment count in {@code metrics}). Selected by
 * {@code reddit.source=redditapi} (the default).
 *
 * <p>The free API needs an app id + secret; when either is blank this returns an empty list rather
 * than calling Reddit, so the agent's multi-source gather soft-fails this source gracefully (e.g. in
 * CI) instead of erroring. A genuine transport failure propagates on the {@link Mono} (the caller's
 * gather soft-fails per source).
 */
@Component
@ConditionalOnProperty(name = "reddit.source", havingValue = "redditapi", matchIfMissing = true)
public class RedditApiSource implements SocialTrendsSource {

    private static final String PERMALINK_BASE = "https://www.reddit.com";
    private static final int SUMMARY_MAX = 500;
    private static final int MAX_API_RESULTS = 100;

    private final WebClient auth;
    private final WebClient api;
    private final McpRedditProperties props;

    public RedditApiSource(@Qualifier("redditAuthWebClient") WebClient auth,
                           @Qualifier("redditApiWebClient") WebClient api,
                           McpRedditProperties props) {
        this.auth = auth;
        this.api = api;
        this.props = props;
    }

    @Override
    public Mono<List<TrendHit>> trends(String subreddit, String query, Integer maxResults) {
        String sub = trim(subreddit);
        String q = trim(query);
        if ((sub == null && q == null)
                || isBlank(props.getClientId()) || isBlank(props.getClientSecret())) {
            return Mono.just(List.of());
        }
        int limit = Math.min(maxResults == null || maxResults <= 0 ? props.getMaxResults() : maxResults, MAX_API_RESULTS);
        return token().flatMap(tok -> listing(tok, sub, q, limit)).map(RedditApiSource::parse);
    }

    private Mono<String> token() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        return auth.post()
                .uri("/api/v1/access_token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(10))
                .map(n -> n == null || n.get("access_token") == null ? "" : n.get("access_token").asText());
    }

    private Mono<JsonNode> listing(String token, String sub, String q, int limit) {
        WebClient.RequestHeadersSpec<?> req;
        if (sub != null && q != null) {
            req = api.get().uri(uri -> uri.path("/r/{sub}/search")
                    .queryParam("q", q)
                    .queryParam("restrict_sr", 1)
                    .queryParam("sort", "hot")
                    .queryParam("limit", limit)
                    .build(sub));
        } else if (sub != null) {
            req = api.get().uri(uri -> uri.path("/r/{sub}/hot")
                    .queryParam("limit", limit)
                    .build(sub));
        } else {
            req = api.get().uri(uri -> uri.path("/search")
                    .queryParam("q", q)
                    .queryParam("sort", "hot")
                    .queryParam("type", "link")
                    .queryParam("limit", limit)
                    .build());
        }
        return req.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(10));
    }

    /** Map a Reddit listing response (or null) to a uniform {@link TrendHit} list. */
    private static List<TrendHit> parse(JsonNode body) {
        List<TrendHit> hits = new ArrayList<>();
        JsonNode children = body == null ? null : body.path("data").get("children");
        if (children == null || !children.isArray()) {
            return hits;
        }
        for (JsonNode child : children) {
            JsonNode d = child.get("data");
            String title = text(d, "title");
            String permalink = text(d, "permalink");
            if (title == null || permalink == null) {
                continue;
            }
            hits.add(new TrendHit(
                    "reddit",
                    "reddit",
                    title,
                    PERMALINK_BASE + permalink,
                    summary(d),
                    metrics(d)));
        }
        return hits;
    }

    private static String summary(JsonNode d) {
        String selftext = text(d, "selftext");
        if (selftext == null) {
            return null;
        }
        return selftext.length() > SUMMARY_MAX ? selftext.substring(0, SUMMARY_MAX) : selftext;
    }

    private static JsonNode metrics(JsonNode d) {
        ObjectNode m = JsonNodeFactory.instance.objectNode();
        String subreddit = text(d, "subreddit");
        if (subreddit != null) {
            m.put("subreddit", subreddit);
        }
        JsonNode score = d.get("score");
        if (score != null && score.isNumber()) {
            m.put("score", score.asInt());
        }
        JsonNode comments = d.get("num_comments");
        if (comments != null && comments.isNumber()) {
            m.put("numComments", comments.asInt());
        }
        return m.isEmpty() ? null : m;
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
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
