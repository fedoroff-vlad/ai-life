package dev.fedorov.ailife.agents.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agents.finance.report.MonthlyReporter;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
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
 * The reactive {@code monthly-report} flow (#196 — the finance domain's first Telegram deliverable).
 * The Coordinator gathers the current month's spend-by-category from mcp-finance's
 * {@code /internal/spending-by-category} passthrough, asks llm-gateway to synthesize a narrative
 * (the {@code monthly-report} SKILL), renders an HTML report board pairing that narrative with a
 * deterministic category breakdown, stores it in media-service and returns the link. An empty month
 * → an invite, no LLM/store. Three MockWebServers stand in for mcp-finance / llm-gateway / media.
 */
@SpringBootTest
class MonthlyReporterTest {

    static MockWebServer mcpFinance;
    static MockWebServer llmGateway;
    static MockWebServer mediaService;

    @BeforeAll
    static void start() throws Exception {
        mcpFinance = new MockWebServer();
        llmGateway = new MockWebServer();
        mediaService = new MockWebServer();
        mcpFinance.start();
        llmGateway.start();
        mediaService.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpFinance.shutdown();
        llmGateway.shutdown();
        mediaService.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("finance-agent.mcp-finance-url", () -> "http://localhost:" + mcpFinance.getPort());
        r.add("finance-agent.media-service-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("finance-agent.public-media-base-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired MonthlyReporter reporter;
    @Autowired ObjectMapper json;

    @Test
    void gathersMonthThenSynthesizesRendersAndLinks() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID storedId = UUID.randomUUID();

        // GET /internal/spending-by-category → this month's categories.
        mcpFinance.enqueue(jsonResponse("[{\"categoryName\":\"Food\",\"currency\":\"EUR\","
                + "\"spent\":300.00,\"txCount\":12},{\"categoryName\":\"Transport\",\"currency\":\"EUR\","
                + "\"spent\":80.00,\"txCount\":4}]"));
        // llm-gateway synthesis (the narrative section).
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large",
                "Больше всего в этом месяце ушло на еду: 300 EUR.",
                "stop", new LlmUsage(40, 20, 60)))));
        // media-service stores the rendered HTML report.
        mediaService.enqueue(jsonResponse(json.writeValueAsString(new MediaObjectDto(
                storedId, householdId, userId, "file", "text/html", 2048, "sha", "finance",
                Instant.now()))));

        var msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "отчёт за месяц", List.of(), "telegram", "9", Instant.now());

        MonthlyReporter.ReportResult result = reporter.report(msg).block();

        assertThat(result).isNotNull();
        assertThat(result.text()).contains("Полный отчёт").contains(storedId.toString());
        assertThat(result.model()).isEqualTo("mock-large");

        // The month window was gathered.
        RecordedRequest spendReq = mcpFinance.takeRequest(2, TimeUnit.SECONDS);
        assertThat(spendReq.getPath()).startsWith("/internal/spending-by-category");
        assertThat(spendReq.getPath()).contains("householdId=" + householdId);

        // The synthesis prompt carried the SKILL + the gathered month data.
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        String llmBody = llmReq.getBody().readUtf8();
        assertThat(llmBody)
                .contains("byCategory")   // gathered data folded into context
                .contains("Food");

        // The rendered report board (narrative + deterministic breakdown) was uploaded.
        RecordedRequest mediaReq = mediaService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(mediaReq.getPath()).isEqualTo("/v1/media");
        String mediaBody = mediaReq.getBody().readUtf8();
        assertThat(mediaBody)
                .contains("Финансовый отчёт")        // the rendered board title
                .contains("Food")                    // deterministic category line
                .contains("Расходы по категориям");  // the breakdown heading
    }

    @Test
    void emptyMonthInvitesWithoutSynthesis() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // GET /internal/spending-by-category → no spending this month.
        mcpFinance.enqueue(jsonResponse("[]"));

        var msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "финансовый отчёт", List.of(), "telegram", "10", Instant.now());

        MonthlyReporter.ReportResult result = reporter.report(msg).block();

        assertThat(result).isNotNull();
        assertThat(result.text()).contains("трат пока не записано");
        assertThat(result.model()).isNull();

        // Only the spending fetch happened — no LLM, no store.
        RecordedRequest spendReq = mcpFinance.takeRequest(2, TimeUnit.SECONDS);
        assertThat(spendReq.getPath()).startsWith("/internal/spending-by-category");
        assertThat(llmGateway.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(mediaService.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
