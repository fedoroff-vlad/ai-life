package dev.fedorov.ailife.agents.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agents.finance.advisor.FinancialAdvisor;
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
 * The reactive {@code financial-advisor} flow (finance MVP — spending analysis on request).
 * The Coordinator gathers two spend-by-category windows from mcp-finance's
 * {@code /internal/spending-by-category} passthrough and asks llm-gateway to synthesize the
 * analysis. Two MockWebServers stand in for mcp-finance and llm-gateway.
 */
@SpringBootTest
class FinancialAdvisorTest {

    static MockWebServer mcpFinance;
    static MockWebServer llmGateway;

    @BeforeAll
    static void start() throws Exception {
        mcpFinance = new MockWebServer();
        llmGateway = new MockWebServer();
        mcpFinance.start();
        llmGateway.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpFinance.shutdown();
        llmGateway.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("finance-agent.mcp-finance-url", () -> "http://localhost:" + mcpFinance.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired FinancialAdvisor advisor;
    @Autowired ObjectMapper json;

    @Test
    void gathersTwoWindowsAndSynthesizesAnalysis() throws Exception {
        // Two spend-by-category windows (recent + previous) — both are GETs to the same
        // path, dispatched FIFO; each carries a distinct category so we can prove the
        // gathered context reached the LLM.
        mcpFinance.enqueue(spendingResponse("[{\"categoryName\":\"Food\",\"currency\":\"EUR\","
                + "\"spent\":300.00,\"txCount\":12}]"));
        mcpFinance.enqueue(spendingResponse("[{\"categoryName\":\"Food\",\"currency\":\"EUR\","
                + "\"spent\":200.00,\"txCount\":9}]"));
        llmGateway.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new LlmChatResponse(
                        "mock-large",
                        "Больше всего ушло на еду: 300 EUR — на 50% больше прошлого периода.",
                        "stop", new LlmUsage(40, 20, 60)))));

        var msg = new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                "проанализируй мои траты", List.of(), "telegram", "9", Instant.now());

        FinancialAdvisor.AdviceResult result = advisor.advise(msg).block();

        assertThat(result).isNotNull();
        assertThat(result.text()).contains("еду").contains("300 EUR");
        assertThat(result.model()).isEqualTo("mock-large");

        // Two spending windows were gathered.
        RecordedRequest s1 = mcpFinance.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest s2 = mcpFinance.takeRequest(2, TimeUnit.SECONDS);
        assertThat(s1.getPath()).startsWith("/internal/spending-by-category");
        assertThat(s2.getPath()).startsWith("/internal/spending-by-category");

        // The synthesis prompt carried the gathered context (both windows + the data).
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        String body = llmReq.getBody().readUtf8();
        assertThat(body)
                .contains("recent")
                .contains("previous")
                .contains("Food");
    }

    private static MockResponse spendingResponse(String jsonArray) {
        return new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(jsonArray);
    }
}
