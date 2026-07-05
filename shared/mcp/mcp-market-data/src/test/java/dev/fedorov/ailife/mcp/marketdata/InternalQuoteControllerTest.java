package dev.fedorov.ailife.mcp.marketdata;

import dev.fedorov.ailife.contracts.market.Quote;
import dev.fedorov.ailife.contracts.market.QuoteInput;
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
 * MD-a: the {@code POST /internal/quote} passthrough drives the same Stooq read → CSV parse logic
 * as the {@code quote} tool, over a MockWebServer-testable transport (the MCP/SSE transport can't be
 * mocked). A MockWebServer stands in for Stooq; no external network. Full MCP context boots with the
 * one registered tool.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class InternalQuoteControllerTest {

    static MockWebServer stooq;

    @BeforeAll
    static void start() throws Exception {
        stooq = new MockWebServer();
        stooq.start();
    }

    @AfterAll
    static void stop() throws Exception {
        stooq.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("marketdata.stooq-url", () -> "http://localhost:" + stooq.getPort());
    }

    @Autowired WebTestClient web;

    @Test
    void passthroughReadsStooqAndParsesTheRow() throws Exception {
        stooq.enqueue(new MockResponse()
                .setHeader("content-type", "text/csv")
                .setBody("""
                        Symbol,Date,Time,Open,High,Low,Close,Volume
                        AAPL.US,2026-06-19,22:00:04,200.1,202.5,199.8,201.3,40000000
                        """));

        Quote quote = web.post().uri("/internal/quote")
                .bodyValue(new QuoteInput("aapl.us"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Quote.class)
                .returnResult().getResponseBody();

        assertThat(quote).isNotNull();
        assertThat(quote.symbol()).isEqualTo("AAPL.US");
        assertThat(quote.price()).isEqualTo(201.3);          // close
        assertThat(quote.open()).isEqualTo(200.1);
        assertThat(quote.high()).isEqualTo(202.5);
        assertThat(quote.low()).isEqualTo(199.8);
        assertThat(quote.volume()).isEqualTo(40000000L);
        assertThat(quote.asOf()).isEqualTo("2026-06-19 22:00:04");

        RecordedRequest req = stooq.takeRequest();
        assertThat(req.getPath())
                .startsWith("/q/l/")
                .contains("s=aapl.us")
                .contains("e=csv");
    }

    @Test
    void unknownSymbolYieldsNullPriceNotAnError() throws Exception {
        // Stooq returns N/D for everything on an unknown symbol — that's "no data", not a failure.
        stooq.enqueue(new MockResponse()
                .setHeader("content-type", "text/csv")
                .setBody("""
                        Symbol,Date,Time,Open,High,Low,Close,Volume
                        ZZZZ,N/D,N/D,N/D,N/D,N/D,N/D,N/D
                        """));

        Quote quote = web.post().uri("/internal/quote")
                .bodyValue(new QuoteInput("zzzz"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Quote.class)
                .returnResult().getResponseBody();

        assertThat(quote).isNotNull();
        assertThat(quote.symbol()).isEqualTo("ZZZZ");
        assertThat(quote.price()).isNull();
        assertThat(quote.asOf()).isNull();

        stooq.takeRequest();
    }
}
