package dev.fedorov.ailife.agents.finance;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agents.finance.report.YearReporter;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.chart.ChartResult;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import okhttp3.mockwebserver.Dispatcher;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reactive {@code year-report} flow (#291) — the year sibling of {@link MonthlyReporterTest}.
 * YearReporter gathers the year's spend-by-category (one window) plus a per-month trend (one window
 * per elapsed month), synthesizes a narrative, renders a category <b>bar</b> chart and a per-month
 * <b>line</b> chart via mcp-chart-render, then stores an HTML board embedding both charts + the
 * deterministic breakdown and returns the link.
 *
 * <p>mcp-finance and mcp-chart-render use dispatchers (the number of spending windows depends on the
 * current month, and the two chart renders run concurrently); llm-gateway / media use single enqueued
 * responses.
 */
@SpringBootTest
class YearReporterTest {

    static MockWebServer mcpFinance;
    static MockWebServer llmGateway;
    static MockWebServer chartRender;
    static MockWebServer mediaService;
    static final ObjectMapper MAPPER = new ObjectMapper();

    static final UUID BAR_CHART_ID = UUID.randomUUID();
    static final UUID LINE_CHART_ID = UUID.randomUUID();
    /** Chart-render request bodies, captured in the dispatcher (the RecordedRequest body is one-shot). */
    static final List<String> chartBodies = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        mcpFinance = new MockWebServer();
        llmGateway = new MockWebServer();
        chartRender = new MockWebServer();
        mediaService = new MockWebServer();
        mcpFinance.start();
        llmGateway.start();
        chartRender.start();
        mediaService.start();

        // Every spending-by-category window (the year + each month) returns the same categories.
        mcpFinance.setDispatcher(constant("[{\"categoryName\":\"Food\",\"currency\":\"EUR\","
                + "\"spent\":1200.00,\"txCount\":90},{\"categoryName\":\"Transport\",\"currency\":\"EUR\","
                + "\"spent\":400.00,\"txCount\":30}]"));

        // The chart render returns a bar-chart id or a line-chart id depending on the requested type.
        chartRender.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String body = request.getBody().readUtf8();
                chartBodies.add(body);
                UUID id = body.contains("\"type\":\"line\"") ? LINE_CHART_ID : BAR_CHART_ID;
                try {
                    return json(MAPPER.writeValueAsString(new ChartResult(id, "java2d")));
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500);
                }
            }
        });
    }

    @AfterAll
    static void stop() throws Exception {
        mcpFinance.shutdown();
        llmGateway.shutdown();
        chartRender.shutdown();
        mediaService.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("finance-agent.mcp-finance-url", () -> "http://localhost:" + mcpFinance.getPort());
        r.add("finance-agent.mcp-chart-render-url", () -> "http://localhost:" + chartRender.getPort());
        r.add("finance-agent.media-service-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("finance-agent.public-media-base-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired YearReporter reporter;
    @Autowired ObjectMapper json;

    @Test
    void gathersYearAndTrendRendersTwoChartsAndLinks() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID storedId = UUID.randomUUID();
        int year = Instant.now().atZone(java.time.ZoneOffset.UTC).getYear();

        llmGateway.enqueue(json(json.writeValueAsString(new LlmChatResponse(
                "mock-large",
                "За " + year + " год больше всего ушло на еду.",
                "stop", new LlmUsage(60, 30, 90)))));
        mediaService.enqueue(json(json.writeValueAsString(new MediaObjectDto(
                storedId, householdId, userId, "file", "text/html", 4096, "sha", "finance",
                Instant.now()))));

        var msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "отчёт за год", List.of(), "telegram", "12", Instant.now());

        var result = reporter.report(msg).block();

        assertThat(result).isNotNull();
        assertThat(result.text()).contains("Полный отчёт").contains(storedId.toString());
        assertThat(result.model()).isEqualTo("mock-large");

        // First spending window is the whole year (from Jan 1).
        RecordedRequest firstSpend = mcpFinance.takeRequest(2, TimeUnit.SECONDS);
        assertThat(firstSpend.getPath())
                .startsWith("/internal/spending-by-category")
                .contains("householdId=" + householdId)
                .contains("from=" + year + "-01-01");

        // The synthesis prompt carried the year categories AND the per-month trend.
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getBody().readUtf8())
                .contains("byCategory")
                .contains("monthlyTrend")
                .contains("Food");

        // Two charts were rendered: a category bar and a per-month line (bodies captured in the dispatcher).
        assertThat(chartBodies).hasSize(2);
        assertThat(chartBodies).anyMatch(b -> b.contains("\"type\":\"bar\""));
        assertThat(chartBodies).anyMatch(b -> b.contains("\"type\":\"line\""));

        // The stored board embeds BOTH charts + the deterministic breakdown.
        RecordedRequest mediaReq = mediaService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(mediaReq.getPath()).isEqualTo("/v1/media");
        assertThat(mediaReq.getBody().readUtf8())
                .contains("Финансовый отчёт за год")
                .contains(BAR_CHART_ID.toString())
                .contains(LINE_CHART_ID.toString())
                .contains("Расходы по категориям");
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }

    private static Dispatcher constant(String jsonBody) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return json(jsonBody);
            }
        };
    }
}
