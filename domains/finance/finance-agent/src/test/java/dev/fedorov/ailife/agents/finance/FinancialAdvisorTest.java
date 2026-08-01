package dev.fedorov.ailife.agents.finance;

import tools.jackson.databind.ObjectMapper;
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
    static MockWebServer profileService;

    @BeforeAll
    static void start() throws Exception {
        mcpFinance = new MockWebServer();
        llmGateway = new MockWebServer();
        profileService = new MockWebServer();
        mcpFinance.start();
        llmGateway.start();
        profileService.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpFinance.shutdown();
        llmGateway.shutdown();
        profileService.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("finance-agent.mcp-finance-url", () -> "http://localhost:" + mcpFinance.getPort());
        r.add("finance-agent.profile-service-url", () -> "http://localhost:" + profileService.getPort());
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

    @Test
    void sharedScopeUnionsSpendingAcrossHouseholds() throws Exception {
        // The member belongs to two households (personal + shared/family). The shared-scope analysis must
        // read both and MERGE by category: same category in both households → spent/txCount add up.
        UUID personalHh = UUID.randomUUID();
        UUID sharedHh = UUID.randomUUID();
        profileService.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("[\"" + personalHh + "\",\"" + sharedHh + "\"]"));

        // 2 windows × 2 households = 4 spend reads. All identical so the merge is deterministic regardless
        // of fan-out interleaving: each window merges to Food 200.00 / txCount 10.
        String row = "[{\"categoryName\":\"Food\",\"currency\":\"EUR\",\"spent\":100.00,\"txCount\":5}]";
        for (int i = 0; i < 4; i++) {
            mcpFinance.enqueue(spendingResponse(row));
        }
        llmGateway.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new LlmChatResponse(
                        "mock-large",
                        "Семейные траты на еду: 200 EUR за период.",
                        "stop", new LlmUsage(40, 20, 60)))));

        var msg = new NormalizedMessage(UUID.randomUUID(), personalHh, MessageScope.PRIVATE,
                "сколько мы потратили", List.of(), "telegram", "10", Instant.now());

        FinancialAdvisor.AdviceResult result = advisor.advise(msg, true).block();

        assertThat(result).isNotNull();
        assertThat(result.model()).isEqualTo("mock-large");

        // The union was resolved from profile-service, and all four household×window reads were issued.
        RecordedRequest profileReq = profileService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(profileReq.getPath()).contains("/households");
        for (int i = 0; i < 4; i++) {
            RecordedRequest s = mcpFinance.takeRequest(2, TimeUnit.SECONDS);
            assertThat(s.getPath()).startsWith("/internal/spending-by-category");
        }

        // The synthesis prompt carried the MERGED figure (100 + 100), proving the union was summed, not
        // double-listed — and the shared scope was flagged.
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        String body = llmReq.getBody().readUtf8();
        // The payload rides as a JSON string inside the chat body, so its quotes are backslash-escaped.
        assertThat(body).contains("200.00").contains("\\\"scope\\\":\\\"shared\\\"");
    }

    private static MockResponse spendingResponse(String jsonArray) {
        return new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(jsonArray);
    }
}
