package dev.fedorov.ailife.mcp.feeds;

import dev.fedorov.ailife.contracts.trends.FeedItemsInput;
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
 * FE-a: the {@code POST /internal/feed-items} passthrough drives the same fetch → Rome (RSS) / jsoup
 * (Telegram) parse logic as the {@code feed_items} tool, over a MockWebServer-testable transport (the
 * MCP/SSE transport can't be mocked). One MockWebServer stands in for both an RSS host and the
 * Telegram web preview; no external network. Full MCP context boots with the one registered tool.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class InternalFeedItemsControllerTest {

    static final ParameterizedTypeReference<List<TrendHit>> HITS = new ParameterizedTypeReference<>() {};

    static MockWebServer host;

    @BeforeAll
    static void start() throws Exception {
        host = new MockWebServer();
        host.start();
    }

    @AfterAll
    static void stop() throws Exception {
        host.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("feeds.telegram-base-url", () -> "http://localhost:" + host.getPort());
    }

    @Autowired WebTestClient web;

    @Test
    void rssUrlIsParsedByRome() throws Exception {
        host.enqueue(new MockResponse()
                .setHeader("content-type", "application/rss+xml")
                .setBody("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <rss version="2.0">
                          <channel>
                            <title>IT English Blog</title>
                            <item>
                              <title>5 phrasal verbs for code reviews</title>
                              <link>https://blog.example.com/phrasal-verbs</link>
                              <description>A short guide for IT pros.</description>
                              <author>editor@example.com</author>
                              <pubDate>Tue, 03 Jun 2025 09:00:00 GMT</pubDate>
                            </item>
                            <item>
                              <title>Standup vocabulary</title>
                              <link>https://blog.example.com/standup</link>
                              <description>Talk at standups with confidence.</description>
                            </item>
                          </channel>
                        </rss>
                        """));

        String feedUrl = "http://localhost:" + host.getPort() + "/rss.xml";
        List<TrendHit> hits = web.post().uri("/internal/feed-items")
                .bodyValue(new FeedItemsInput(feedUrl, 5))
                .exchange()
                .expectStatus().isOk()
                .expectBody(HITS)
                .returnResult().getResponseBody();

        assertThat(hits).hasSize(2);
        TrendHit first = hits.get(0);
        assertThat(first.source()).isEqualTo("rss");
        assertThat(first.platform()).isEqualTo("rss");
        assertThat(first.title()).isEqualTo("5 phrasal verbs for code reviews");
        assertThat(first.url()).isEqualTo("https://blog.example.com/phrasal-verbs");
        assertThat(first.summary()).isEqualTo("A short guide for IT pros.");
        assertThat(first.metrics().get("author").asText()).isEqualTo("editor@example.com");
        assertThat(first.metrics().get("publishedAt").asText()).startsWith("2025-06-03T09:00");

        RecordedRequest req = host.takeRequest();
        assertThat(req.getPath()).isEqualTo("/rss.xml");
    }

    @Test
    void telegramChannelIsParsedByJsoupNewestFirst() throws Exception {
        host.enqueue(new MockResponse()
                .setHeader("content-type", "text/html")
                .setBody("""
                        <html><body>
                          <div class="tgme_widget_message">
                            <a class="tgme_widget_message_date" href="https://t.me/itenglish/10"><time>old</time></a>
                            <div class="tgme_widget_message_text">Older post about grammar</div>
                          </div>
                          <div class="tgme_widget_message">
                            <a class="tgme_widget_message_date" href="https://t.me/itenglish/11"><time>new</time></a>
                            <div class="tgme_widget_message_text">Newest post about interviews</div>
                          </div>
                        </body></html>
                        """));

        List<TrendHit> hits = web.post().uri("/internal/feed-items")
                .bodyValue(new FeedItemsInput("@itenglish", 5))
                .exchange()
                .expectStatus().isOk()
                .expectBody(HITS)
                .returnResult().getResponseBody();

        assertThat(hits).hasSize(2);
        TrendHit newest = hits.get(0);
        assertThat(newest.source()).isEqualTo("telegram");
        assertThat(newest.platform()).isEqualTo("telegram");
        assertThat(newest.title()).isEqualTo("Newest post about interviews");
        assertThat(newest.url()).isEqualTo("https://t.me/itenglish/11");
        assertThat(newest.summary()).isEqualTo("Newest post about interviews");
        assertThat(newest.metrics().get("channel").asText()).isEqualTo("itenglish");

        RecordedRequest req = host.takeRequest();
        assertThat(req.getPath()).isEqualTo("/s/itenglish");
    }

    @Test
    void blankSourceShortCircuitsWithNoFetch() {
        // No enqueue: a blank source must NOT fetch anything — returns empty immediately. Assert no
        // NEW request was dispatched (the count is cumulative across this class's shared server).
        int before = host.getRequestCount();

        List<TrendHit> hits = web.post().uri("/internal/feed-items")
                .bodyValue(new FeedItemsInput("  ", 5))
                .exchange()
                .expectStatus().isOk()
                .expectBody(HITS)
                .returnResult().getResponseBody();

        assertThat(hits).isEmpty();
        assertThat(host.getRequestCount()).isEqualTo(before);
    }
}
