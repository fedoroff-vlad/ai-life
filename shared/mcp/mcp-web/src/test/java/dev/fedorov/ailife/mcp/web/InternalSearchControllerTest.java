package dev.fedorov.ailife.mcp.web;

import dev.fedorov.ailife.contracts.web.WebSearchInput;
import dev.fedorov.ailife.contracts.web.WebSearchResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-a: the {@code POST /internal/search} passthrough drives the same SearXNG query → parse logic
 * as the {@code web_search} tool, over a MockWebServer-testable transport (the MCP/SSE transport
 * can't be mocked). A MockWebServer stands in for SearXNG; no external network.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class InternalSearchControllerTest {

    static MockWebServer searxng;

    @BeforeAll
    static void start() throws Exception {
        searxng = new MockWebServer();
        searxng.start();
    }

    @AfterAll
    static void stop() throws Exception {
        searxng.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("mcp-web.searxng-url", () -> "http://localhost:" + searxng.getPort());
    }

    @Autowired WebTestClient web;

    @Test
    void passthroughQueriesSearxngAndParsesHits() throws Exception {
        searxng.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("""
                        {"query":"3d printer bed leveling","results":[
                          {"url":"https://example.com/guide","title":"Bed Leveling Guide","content":"Step by step."},
                          {"url":"https://youtube.com/watch?v=abc","title":"Leveling Video","content":"Watch."},
                          {"title":"No URL — dropped","content":"ignored"}
                        ]}"""));

        WebSearchResult result = web.post().uri("/internal/search")
                .bodyValue(new WebSearchInput("3d printer bed leveling", 5))
                .exchange()
                .expectStatus().isOk()
                .expectBody(WebSearchResult.class)
                .returnResult().getResponseBody();

        assertThat(result).isNotNull();
        assertThat(result.query()).isEqualTo("3d printer bed leveling");
        // The URL-less hit is dropped; the two real hits survive in engine order.
        assertThat(result.hits()).hasSize(2);
        assertThat(result.hits().get(0).url()).isEqualTo("https://example.com/guide");
        assertThat(result.hits().get(0).title()).isEqualTo("Bed Leveling Guide");
        assertThat(result.hits().get(0).snippet()).isEqualTo("Step by step.");
        assertThat(result.hits().get(1).url()).contains("youtube.com");

        RecordedRequest req = searxng.takeRequest();
        assertThat(req.getPath())
                .startsWith("/search")
                .contains("format=json")
                .contains("q=3d");
    }

    @Test
    void limitTrimsTheHitList() throws Exception {
        searxng.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("""
                        {"results":[
                          {"url":"https://a.com","title":"a","content":"a"},
                          {"url":"https://b.com","title":"b","content":"b"},
                          {"url":"https://c.com","title":"c","content":"c"}
                        ]}"""));

        WebSearchResult result = web.post().uri("/internal/search")
                .bodyValue(new WebSearchInput("anything", 2))
                .exchange()
                .expectStatus().isOk()
                .expectBody(WebSearchResult.class)
                .returnResult().getResponseBody();

        assertThat(result).isNotNull();
        assertThat(result.hits()).hasSize(2);

        // Drain this test's request so it doesn't leak into the shared static server's queue.
        searxng.takeRequest();
    }
}
