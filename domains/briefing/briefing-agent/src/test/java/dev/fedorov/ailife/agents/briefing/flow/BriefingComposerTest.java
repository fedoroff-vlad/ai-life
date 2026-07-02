package dev.fedorov.ailife.agents.briefing.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
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
class BriefingComposerTest {

    static MockWebServer mcpBriefing;
    static MockWebServer mcpWeather;
    static MockWebServer mcpCaldav;
    static MockWebServer mcpFinance;
    static MockWebServer mcpWeb;
    static MockWebServer mediaService;
    static MockWebServer llmGateway;

    @BeforeAll
    static void start() throws Exception {
        mcpBriefing = new MockWebServer();
        mcpWeather = new MockWebServer();
        mcpCaldav = new MockWebServer();
        mcpFinance = new MockWebServer();
        mcpWeb = new MockWebServer();
        mediaService = new MockWebServer();
        llmGateway = new MockWebServer();
        mcpBriefing.start();
        mcpWeather.start();
        mcpCaldav.start();
        mcpFinance.start();
        mcpWeb.start();
        mediaService.start();
        llmGateway.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpBriefing.shutdown();
        mcpWeather.shutdown();
        mcpCaldav.shutdown();
        mcpFinance.shutdown();
        mcpWeb.shutdown();
        mediaService.shutdown();
        llmGateway.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("briefing-agent.mcp-briefing-url", () -> "http://localhost:" + mcpBriefing.getPort());
        r.add("briefing-agent.mcp-weather-url", () -> "http://localhost:" + mcpWeather.getPort());
        r.add("briefing-agent.mcp-caldav-url", () -> "http://localhost:" + mcpCaldav.getPort());
        r.add("briefing-agent.mcp-finance-url", () -> "http://localhost:" + mcpFinance.getPort());
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
        mcpFinance.setDispatcher(fixedJson(json.writeValueAsString(List.of(new SpendingByCategoryRow(
                UUID.randomUUID(), "Groceries", "RUB", new BigDecimal("1234.50"), 3)))));
        mcpWeb.setDispatcher(fixedJson(json.writeValueAsString(new WebSearchResult("AI", List.of(
                new WebSearchHit("AI breakthrough", "https://example.com/ai", "A new model shipped."))))));
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

        // The one synthesis turn carried every gathered section (weather + agenda + finance + news).
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
        mcpFinance.setDispatcher(fixedJson(json.writeValueAsString(List.of(new SpendingByCategoryRow(
                UUID.randomUUID(), "Transport", "RUB", new BigDecimal("300.00"), 2)))));
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

        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        String body = llmReq.getBody().readUtf8();
        assertThat(body).contains("Dentist").contains("Transport");
    }

    private IntentResponse post(NormalizedMessage msg) {
        return http.post().uri("/agents/briefing/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();
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
