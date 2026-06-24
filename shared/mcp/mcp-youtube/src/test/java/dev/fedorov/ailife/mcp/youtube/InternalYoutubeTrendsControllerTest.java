package dev.fedorov.ailife.mcp.youtube;

import dev.fedorov.ailife.contracts.trends.TrendHit;
import dev.fedorov.ailife.contracts.trends.YoutubeTrendsInput;
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
 * YT-a: the {@code POST /internal/youtube-trends} passthrough drives the same YouTube Data API read →
 * JSON parse logic as the {@code youtube_trends} tool, over a MockWebServer-testable transport (the
 * MCP/SSE transport can't be mocked). A MockWebServer stands in for the YouTube Data API; no external
 * network and a dummy key. Full MCP context boots with the one registered tool.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class InternalYoutubeTrendsControllerTest {

    static final ParameterizedTypeReference<List<TrendHit>> HITS = new ParameterizedTypeReference<>() {};

    static MockWebServer yt;

    @BeforeAll
    static void start() throws Exception {
        yt = new MockWebServer();
        yt.start();
    }

    @AfterAll
    static void stop() throws Exception {
        yt.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("youtube.api-base-url", () -> "http://localhost:" + yt.getPort());
        r.add("youtube.api-key", () -> "test-key");
    }

    @Autowired WebTestClient web;

    @Test
    void searchResponseMapsToTrendHits() throws Exception {
        yt.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("""
                        {
                          "items": [
                            {
                              "id": { "kind": "youtube#video", "videoId": "abc123" },
                              "snippet": {
                                "publishedAt": "2026-06-01T10:00:00Z",
                                "channelTitle": "English4IT",
                                "title": "10 idioms every IT pro should know",
                                "description": "Quick rundown of common idioms."
                              }
                            },
                            {
                              "id": { "kind": "youtube#video", "videoId": "def456" },
                              "snippet": {
                                "publishedAt": "2026-05-20T08:30:00Z",
                                "channelTitle": "DevEnglish",
                                "title": "Standup phrases in English",
                                "description": "How to speak at standups."
                              }
                            }
                          ]
                        }
                        """));

        List<TrendHit> hits = web.post().uri("/internal/youtube-trends")
                .bodyValue(new YoutubeTrendsInput("English for IT", 2))
                .exchange()
                .expectStatus().isOk()
                .expectBody(HITS)
                .returnResult().getResponseBody();

        assertThat(hits).hasSize(2);
        TrendHit first = hits.get(0);
        assertThat(first.source()).isEqualTo("youtube");
        assertThat(first.platform()).isEqualTo("youtube");
        assertThat(first.title()).isEqualTo("10 idioms every IT pro should know");
        assertThat(first.url()).isEqualTo("https://www.youtube.com/watch?v=abc123");
        assertThat(first.summary()).isEqualTo("Quick rundown of common idioms.");
        assertThat(first.metrics().get("channel").asText()).isEqualTo("English4IT");
        assertThat(first.metrics().get("publishedAt").asText()).isEqualTo("2026-06-01T10:00:00Z");

        RecordedRequest req = yt.takeRequest();
        assertThat(req.getPath())
                .startsWith("/search")
                .contains("part=snippet")
                .contains("type=video")
                .contains("q=English%20for%20IT")
                .contains("maxResults=2")
                .contains("key=test-key");
    }

    @Test
    void noMatchesYieldsEmptyListNotAnError() {
        yt.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("""
                        { "items": [] }
                        """));

        List<TrendHit> hits = web.post().uri("/internal/youtube-trends")
                .bodyValue(new YoutubeTrendsInput("zzzznotopic", null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(HITS)
                .returnResult().getResponseBody();

        assertThat(hits).isEmpty();
    }

    @Test
    void blankQueryShortCircuitsWithNoApiCall() {
        // No enqueue: a blank query must NOT call the upstream — it returns empty immediately.
        List<TrendHit> hits = web.post().uri("/internal/youtube-trends")
                .bodyValue(new YoutubeTrendsInput("  ", 5))
                .exchange()
                .expectStatus().isOk()
                .expectBody(HITS)
                .returnResult().getResponseBody();

        assertThat(hits).isEmpty();
        assertThat(yt.getRequestCount()).isZero();
    }
}
