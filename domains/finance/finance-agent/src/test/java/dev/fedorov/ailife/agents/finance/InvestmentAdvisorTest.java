package dev.fedorov.ailife.agents.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agents.finance.advisor.InvestmentAdvisor;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reactive {@code investment-advisor} flow (advisory only). The Coordinator gathers a
 * {@code quote} per named symbol from mcp-market-data's {@code /internal/quote} passthrough and asks
 * llm-gateway to synthesize considerations. Two MockWebServers stand in for mcp-market-data and
 * llm-gateway — no native dep, no real market call.
 */
@SpringBootTest
class InvestmentAdvisorTest {

    static MockWebServer marketData;
    static MockWebServer llmGateway;

    @BeforeAll
    static void start() throws Exception {
        marketData = new MockWebServer();
        llmGateway = new MockWebServer();
        marketData.start();
        llmGateway.start();
    }

    @AfterAll
    static void stop() throws Exception {
        marketData.shutdown();
        llmGateway.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("finance-agent.mcp-market-data-url", () -> "http://localhost:" + marketData.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired InvestmentAdvisor advisor;
    @Autowired ObjectMapper json;

    @Test
    void gathersAQuotePerSymbolAndSynthesizesConsiderations() throws Exception {
        // Two quotes (one per symbol) — both POSTs to /internal/quote, dispatched FIFO; each carries
        // a distinct symbol/price so we can prove the gathered context reached the LLM.
        marketData.enqueue(quoteResponse("{\"symbol\":\"AAPL.US\",\"price\":201.3,"
                + "\"asOf\":\"2026-06-19 22:00:04\",\"open\":200.1,\"high\":202.5,\"low\":199.8}"));
        marketData.enqueue(quoteResponse("{\"symbol\":\"XAUUSD\",\"price\":2310.5,"
                + "\"asOf\":\"2026-06-19 22:00:04\"}"));
        llmGateway.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new LlmChatResponse(
                        "mock-large",
                        "Apple ~201 USD, золото ~2310. Это информация к размышлению — решать вам.",
                        "stop", new LlmUsage(50, 25, 75)))));

        var msg = new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                "что думаешь про Apple и золото?", List.of(), "telegram", "11", Instant.now());

        InvestmentAdvisor.AdviceResult result =
                advisor.advise(msg, List.of("aapl.us", "xauusd")).block();

        assertThat(result).isNotNull();
        assertThat(result.text()).contains("Apple").contains("решать вам");
        assertThat(result.model()).isEqualTo("mock-large");

        // A quote was gathered per symbol.
        RecordedRequest q1 = marketData.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest q2 = marketData.takeRequest(2, TimeUnit.SECONDS);
        assertThat(q1.getPath()).isEqualTo("/internal/quote");
        assertThat(q2.getPath()).isEqualTo("/internal/quote");

        // The synthesis prompt carried the gathered context (both symbols + their prices).
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        String body = llmReq.getBody().readUtf8();
        assertThat(body)
                .contains("aapl.us")
                .contains("xauusd")
                .contains("201.3");
    }

    @Test
    void noSymbolsReturnsAnInviteWithoutCallingTheCapability() {
        InvestmentAdvisor.AdviceResult result = advisor.advise(
                new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                        "посоветуй что купить", List.of(), "telegram", "12", Instant.now()),
                List.of()).block();

        assertThat(result).isNotNull();
        // No symbols → short-circuits to an invite (no enqueued quote is consumed, so the
        // flow can't have hit the capability) without an LLM synthesis.
        assertThat(result.text()).contains("тикеры");
        assertThat(result.model()).isNull();
    }

    private static MockResponse quoteResponse(String jsonObject) {
        return new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(jsonObject);
    }
}
