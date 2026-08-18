package dev.fedorov.ailife.agents.briefing.flow;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.transparency.DegradedNotice;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.briefing.BriefingProfileDto;
import dev.fedorov.ailife.contracts.calendar.CalendarEventDto;
import dev.fedorov.ailife.contracts.finance.SpendingByCategoryRow;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.contracts.weather.Weather;
import dev.fedorov.ailife.contracts.web.WebSearchHit;
import dev.fedorov.ailife.contracts.web.WebSearchResult;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the digest flow (BR-d) through the agent's HTTP surface ({@code POST /agents/briefing/intent})
 * with a produce-now cue: resolve the profile → gather weather + today's agenda + yesterday's spend +
 * news (all over the {@code /internal/*} passthroughs, in parallel) → one {@code briefing-composer} LLM
 * synthesis. MockWebServers stand in for mcp-briefing, mcp-weather, mcp-caldav, mcp-finance, mcp-web, and
 * llm-gateway; the source servers use fixed dispatchers (order-independent for the parallel gather), and
 * we assert the synthesis request carried every gathered section.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class BriefingComposerTest {

    static MockWebServer mcpBriefing;
    static MockWebServer mcpWeather;
    static MockWebServer mcpCaldav;
    static MockWebServer orchestrator;
    static MockWebServer mcpWeb;
    static MockWebServer mediaService;
    static MockWebServer llmGateway;

    @BeforeAll
    static void start() throws Exception {
        mcpBriefing = new MockWebServer();
        mcpWeather = new MockWebServer();
        mcpCaldav = new MockWebServer();
        orchestrator = new MockWebServer();
        mcpWeb = new MockWebServer();
        mediaService = new MockWebServer();
        llmGateway = new MockWebServer();
        mcpBriefing.start();
        mcpWeather.start();
        mcpCaldav.start();
        orchestrator.start();
        mcpWeb.start();
        mediaService.start();
        llmGateway.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpBriefing.shutdown();
        mcpWeather.shutdown();
        mcpCaldav.shutdown();
        orchestrator.shutdown();
        mcpWeb.shutdown();
        mediaService.shutdown();
        llmGateway.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("briefing-agent.mcp-briefing-url", () -> "http://localhost:" + mcpBriefing.getPort());
        r.add("briefing-agent.mcp-weather-url", () -> "http://localhost:" + mcpWeather.getPort());
        r.add("briefing-agent.mcp-caldav-url", () -> "http://localhost:" + mcpCaldav.getPort());
        r.add("briefing-agent.orchestrator-url", () -> "http://localhost:" + orchestrator.getPort());
        r.add("briefing-agent.mcp-web-url", () -> "http://localhost:" + mcpWeb.getPort());
        r.add("briefing-agent.media-service-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("briefing-agent.public-media-base-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void gathersEveryEnabledSectionAndSynthesizesOneBriefing() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // A profile with coordinates + one interest + all four sections → every gather step runs.
        mcpBriefing.setDispatcher(fixedJson(json.writeValueAsString(new BriefingProfileDto(
                UUID.randomUUID(), householdId, userId, "Москва", 55.75, 37.62, "Europe/Moscow",
                json.readTree("[\"AI\"]"), json.readTree("[\"weather\",\"agenda\",\"finance\",\"news\"]"),
                "08:00", true, null, Instant.now()))));
        mcpWeather.setDispatcher(fixedJson(json.writeValueAsString(new Weather(
                55.75, 37.62, "2026-07-02", 25.0, 15.0, 10, 12.0, 1, "Mainly clear"))));
        mcpCaldav.setDispatcher(fixedJson(json.writeValueAsString(List.of(new CalendarEventDto(
                UUID.randomUUID(), householdId, "personal", "uid-1", "Standup", null, "Zoom",
                Instant.parse("2026-07-02T07:00:00Z"), Instant.parse("2026-07-02T07:15:00Z"),
                null, List.of(), null)))));
        ObjectNode spend1 = json.createObjectNode();
        spend1.set("spending", json.valueToTree(List.of(new SpendingByCategoryRow(
                UUID.randomUUID(), "Groceries", "RUB", new BigDecimal("1234.50"), 3))));
        orchestrator.setDispatcher(fixedJson(json.writeValueAsString(AgentActionResult.ok(spend1))));
        mcpWeb.setDispatcher(fixedJson(json.writeValueAsString(new WebSearchResult("AI", List.of(
                new WebSearchHit("AI breakthrough", "https://example.com/ai", "A new model shipped."))))));
        // Two LLM turns now (#475): the BriefingIntentRouter classifies (→ briefing-composer), then the synthesis.
        enqueuePick("briefing-composer");
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "Доброе утро! Погода ясная, есть встречи и расходы.", "stop",
                new LlmUsage(200, 80, 280)))));
        UUID storedId = UUID.randomUUID();
        mediaService.enqueue(jsonResponse(json.writeValueAsString(new MediaObjectDto(
                storedId, householdId, userId, "file", "text/html", 4096, "sha", "briefing", Instant.now()))));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "собери мне брифинг на сегодня", List.of(), "telegram", "80", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        // The synthesized text plus the stored board link (BR-e).
        assertThat(resp.text()).contains("Доброе утро! Погода ясная, есть встречи и расходы.")
                .contains(storedId.toString());

        // The rendered board (text/html) was uploaded to media-service.
        RecordedRequest mediaReq = mediaService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(mediaReq.getPath()).isEqualTo("/v1/media");

        // First the router classify, then the one synthesis turn carried every gathered section.
        llmGateway.takeRequest(2, TimeUnit.SECONDS);          // router classify
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        String body = llmReq.getBody().readUtf8();
        assertThat(body).contains("Mainly clear")   // weather
                .contains("Standup")                 // agenda
                .contains("Groceries")               // finance
                .contains("AI breakthrough");        // news
    }

    @Test
    void noProfileFallsBackToAgendaAndFinanceOnly() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // No profile set (404 for self + household) → default all-sections, but no coords (weather
        // skipped) and no interests (news skipped); agenda + finance still gather.
        mcpBriefing.setDispatcher(notFound());
        mcpCaldav.setDispatcher(fixedJson(json.writeValueAsString(List.of(new CalendarEventDto(
                UUID.randomUUID(), householdId, "personal", "uid-2", "Dentist", null, null,
                Instant.parse("2026-07-02T09:00:00Z"), Instant.parse("2026-07-02T09:30:00Z"),
                null, List.of(), null)))));
        ObjectNode spend2 = json.createObjectNode();
        spend2.set("spending", json.valueToTree(List.of(new SpendingByCategoryRow(
                UUID.randomUUID(), "Transport", "RUB", new BigDecimal("300.00"), 2))));
        orchestrator.setDispatcher(fixedJson(json.writeValueAsString(AgentActionResult.ok(spend2))));
        enqueuePick("briefing-composer");                    // router classify → briefing-composer
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "Сегодня: приём и расходы на транспорт.", "stop",
                new LlmUsage(120, 40, 160)))));
        UUID storedId = UUID.randomUUID();
        mediaService.enqueue(jsonResponse(json.writeValueAsString(new MediaObjectDto(
                storedId, householdId, userId, "file", "text/html", 2048, "sha", "briefing", Instant.now()))));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "брифинг на сегодня", List.of(), "telegram", "81", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Сегодня: приём и расходы на транспорт.")
                .contains(storedId.toString());

        // Weather + news were skipped entirely (no coords / no interests) — those servers got no request.
        assertThat(mcpWeather.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(mcpWeb.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();

        llmGateway.takeRequest(2, TimeUnit.SECONDS);          // router classify
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        String body = llmReq.getBody().readUtf8();
        assertThat(body).contains("Dentist").contains("Transport");
    }

    @Test
    void mediaStoreFailureSurfacesDegradedNotice() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // Modelled on the no-profile path (agenda + finance only — no weather/news gather, so no extra
        // MockWebServer footprint) but the board store fails (media-service 500). The digest must survive
        // as text and honestly say the board is missing, not pretend a full digest (#485).
        mcpBriefing.setDispatcher(notFound());
        mcpCaldav.setDispatcher(fixedJson(json.writeValueAsString(List.of(new CalendarEventDto(
                UUID.randomUUID(), householdId, "personal", "uid-3", "Standup", null, null,
                Instant.parse("2026-07-02T07:00:00Z"), Instant.parse("2026-07-02T07:15:00Z"),
                null, List.of(), null)))));
        ObjectNode spend = json.createObjectNode();
        spend.set("spending", json.valueToTree(List.of(new SpendingByCategoryRow(
                UUID.randomUUID(), "Groceries", "RUB", new BigDecimal("1234.50"), 3))));
        orchestrator.setDispatcher(fixedJson(json.writeValueAsString(AgentActionResult.ok(spend))));
        enqueuePick("briefing-composer");
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "Доброе утро! Есть встречи и расходы.", "stop", new LlmUsage(200, 80, 280)))));
        // Board upload fails.
        mediaService.enqueue(new MockResponse().setResponseCode(500));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "собери мне брифинг на сегодня", List.of(), "telegram", "82", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        // Text preserved, honest degraded notice present, and no fake board link.
        assertThat(resp.text())
                .contains("Доброе утро! Есть встречи и расходы.")
                .contains(DegradedNotice.MARKER)
                .doesNotContain("/v1/media/");

        // Drain this test's two llm-gateway requests (classify + synthesis) so the shared static
        // MockWebServer's recorded-request queue stays balanced for the other methods (mirrors them).
        llmGateway.takeRequest(2, TimeUnit.SECONDS);
        llmGateway.takeRequest(2, TimeUnit.SECONDS);
    }

    private IntentResponse post(NormalizedMessage msg) {
        return http.post().uri("/agents/briefing/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();
    }

    /** The BriefingIntentRouter's classify turn (#475) that routes the text to {@code skill} before its flow runs. */
    private void enqueuePick(String skill) throws Exception {
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{\"action\":\"skill\",\"name\":\"" + skill + "\"}", "stop",
                new LlmUsage(20, 8, 28)))));
    }

    private static Dispatcher fixedJson(String body) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return jsonResponse(body);
            }
        };
    }

    private static Dispatcher notFound() {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(404);
            }
        };
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
