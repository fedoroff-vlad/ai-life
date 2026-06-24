package dev.fedorov.ailife.mcp.reddit;

import dev.fedorov.ailife.contracts.trends.RedditTrendsInput;
import dev.fedorov.ailife.contracts.trends.TrendHit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RD-a: the {@code POST /internal/reddit-trends} passthrough drives the same app-only OAuth → listing
 * read → JSON parse logic as the {@code reddit_trends} tool, over a MockWebServer-testable transport
 * (the MCP/SSE transport can't be mocked). One MockWebServer stands in for both the Reddit auth host
 * and the API host; no external network, dummy app creds. Full MCP context boots with the one
 * registered tool.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class InternalRedditTrendsControllerTest {

    static final ParameterizedTypeReference<List<TrendHit>> HITS = new ParameterizedTypeReference<>() {};

    static MockWebServer reddit;

    @BeforeAll
    static void start() throws Exception {
        reddit = new MockWebServer();
        reddit.start();
    }

    @AfterAll
    static void stop() throws Exception {
        reddit.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        String base = "http://localhost:" + reddit.getPort();
        r.add("reddit.api-base-url", () -> base);
        r.add("reddit.auth-base-url", () -> base);
        r.add("reddit.client-id", () -> "test-id");
        r.add("reddit.client-secret", () -> "test-secret");
    }

    @Autowired WebTestClient web;

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }

    @Test
    void subredditHotMintsTokenThenMapsListingToTrendHits() throws Exception {
        reddit.enqueue(json("""
                { "access_token": "tok-123", "token_type": "bearer", "expires_in": 3600 }
                """));
        reddit.enqueue(json("""
                {
                  "data": {
                    "children": [
                      {
                        "data": {
                          "title": "How I learned IT English in 3 months",
                          "permalink": "/r/EnglishLearning/comments/abc/how_i_learned/",
                          "selftext": "Here is what worked for me.",
                          "subreddit": "EnglishLearning",
                          "score": 1280,
                          "num_comments": 95
                        }
                      }
                    ]
                  }
                }
                """));

        List<TrendHit> hits = web.post().uri("/internal/reddit-trends")
                .bodyValue(new RedditTrendsInput("EnglishLearning", null, 5))
                .exchange()
                .expectStatus().isOk()
                .expectBody(HITS)
                .returnResult().getResponseBody();

        assertThat(hits).hasSize(1);
        TrendHit hit = hits.get(0);
        assertThat(hit.source()).isEqualTo("reddit");
        assertThat(hit.platform()).isEqualTo("reddit");
        assertThat(hit.title()).isEqualTo("How I learned IT English in 3 months");
        assertThat(hit.url()).isEqualTo("https://www.reddit.com/r/EnglishLearning/comments/abc/how_i_learned/");
        assertThat(hit.summary()).isEqualTo("Here is what worked for me.");
        assertThat(hit.metrics().get("subreddit").asText()).isEqualTo("EnglishLearning");
        assertThat(hit.metrics().get("score").asInt()).isEqualTo(1280);
        assertThat(hit.metrics().get("numComments").asInt()).isEqualTo(95);

        RecordedRequest tokenReq = reddit.takeRequest();
        assertThat(tokenReq.getPath()).isEqualTo("/api/v1/access_token");
        assertThat(tokenReq.getHeader("Authorization")).startsWith("Basic ");
        assertThat(tokenReq.getBody().readUtf8()).contains("grant_type=client_credentials");

        RecordedRequest listReq = reddit.takeRequest();
        assertThat(listReq.getPath())
                .startsWith("/r/EnglishLearning/hot")
                .contains("limit=5");
        assertThat(listReq.getHeader("Authorization")).isEqualTo("Bearer tok-123");
    }

    @Test
    void queryOnlyUsesGlobalSearch() throws Exception {
        reddit.enqueue(json("""
                { "access_token": "tok-xyz", "token_type": "bearer" }
                """));
        reddit.enqueue(json("""
                { "data": { "children": [
                  { "data": { "title": "Best IT English channels", "permalink": "/r/x/c/1/", "subreddit": "x", "score": 10 } }
                ] } }
                """));

        List<TrendHit> hits = web.post().uri("/internal/reddit-trends")
                .bodyValue(new RedditTrendsInput(null, "IT English", null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(HITS)
                .returnResult().getResponseBody();

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).title()).isEqualTo("Best IT English channels");
        assertThat(hits.get(0).summary()).isNull();

        reddit.takeRequest(); // token
        RecordedRequest listReq = reddit.takeRequest();
        assertThat(listReq.getPath())
                .startsWith("/search")
                .contains("q=IT%20English")
                .contains("type=link");
    }

    @Test
    void noTargetShortCircuitsWithNoApiCall() {
        // No enqueue: a request with neither subreddit nor query must NOT call Reddit — returns empty.
        List<TrendHit> hits = web.post().uri("/internal/reddit-trends")
                .bodyValue(new RedditTrendsInput("  ", null, 5))
                .exchange()
                .expectStatus().isOk()
                .expectBody(HITS)
                .returnResult().getResponseBody();

        assertThat(hits).isEmpty();
        assertThat(reddit.getRequestCount()).isZero();
    }
}
