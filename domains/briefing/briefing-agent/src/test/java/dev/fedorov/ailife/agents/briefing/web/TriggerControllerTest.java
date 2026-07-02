package dev.fedorov.ailife.agents.briefing.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.briefing.BriefingProfileDto;
import dev.fedorov.ailife.contracts.calendar.CalendarEventDto;
import dev.fedorov.ailife.contracts.finance.SpendingByCategoryRow;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.contracts.profile.UserDto;
import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import dev.fedorov.ailife.contracts.weather.Weather;
import dev.fedorov.ailife.contracts.web.WebSearchHit;
import dev.fedorov.ailife.contracts.web.WebSearchResult;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
 * Exercises the proactive wake path (BR-f1) through the trigger surface
 * ({@code POST /agents/briefing/triggers/{kind}}): a {@code briefing.digest} wake reuses the same
 * {@link dev.fedorov.ailife.agents.briefing.flow.BriefingComposer} digest flow (empty user text) and
 * delivers the result via notifier-service — to the profile owner when the payload names one, else to
 * every household user. MockWebServers stand in for the gather sources, media-service, llm-gateway,
 * profile-service, and notifier-service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TriggerControllerTest {

    static MockWebServer mcpBriefing;
    static MockWebServer mcpWeather;
    static MockWebServer mcpCaldav;
    static MockWebServer mcpFinance;
    static MockWebServer mcpWeb;
    static MockWebServer mediaService;
    static MockWebServer llmGateway;
    static MockWebServer profileService;
    static MockWebServer notifier;

    @BeforeAll
    static void start() throws Exception {
        mcpBriefing = new MockWebServer();
        mcpWeather = new MockWebServer();
        mcpCaldav = new MockWebServer();
        mcpFinance = new MockWebServer();
        mcpWeb = new MockWebServer();
        mediaService = new MockWebServer();
        llmGateway = new MockWebServer();
        profileService = new MockWebServer();
        notifier = new MockWebServer();
        for (MockWebServer s : List.of(mcpBriefing, mcpWeather, mcpCaldav, mcpFinance, mcpWeb,
                mediaService, llmGateway, profileService, notifier)) {
            s.start();
        }
    }

    @AfterAll
    static void stop() throws Exception {
        for (MockWebServer s : List.of(mcpBriefing, mcpWeather, mcpCaldav, mcpFinance, mcpWeb,
                mediaService, llmGateway, profileService, notifier)) {
            s.shutdown();
        }
    }

    /** Servers are shared (static), so drain every server's recorded-request queue between tests —
     *  otherwise a prior test's request leaks into a later {@code takeRequest} (negative) assertion. */
    @AfterEach
    void drain() throws Exception {
        for (MockWebServer s : List.of(mcpBriefing, mcpWeather, mcpCaldav, mcpFinance, mcpWeb,
                mediaService, llmGateway, profileService, notifier)) {
            while (s.takeRequest(1, TimeUnit.MILLISECONDS) != null) {
                // discard
            }
        }
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
        r.add("briefing-agent.profile-service-url", () -> "http://localhost:" + profileService.getPort());
        r.add("briefing-agent.notifier-url", () -> "http://localhost:" + notifier.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void digestWakeComposesAndNotifiesTheOwner() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        // ownerId profile with coords + one interest + all sections → full gather.
        mcpBriefing.setDispatcher(fixedJson(json.writeValueAsString(new BriefingProfileDto(
                UUID.randomUUID(), householdId, ownerId, "Москва", 55.75, 37.62, "Europe/Moscow",
                json.readTree("[\"AI\"]"), json.readTree("[\"weather\",\"agenda\",\"finance\",\"news\"]"),
                "08:00", true, null, Instant.now()))));
        mcpWeather.setDispatcher(fixedJson(json.writeValueAsString(new Weather(
                55.75, 37.62, "2026-07-02", 25.0, 15.0, 10, 12.0, 1, "Mainly clear"))));
        mcpCaldav.setDispatcher(fixedJson(json.writeValueAsString(List.of(new CalendarEventDto(
                UUID.randomUUID(), householdId, "personal", "uid-1", "Standup", null, null,
                Instant.parse("2026-07-02T07:00:00Z"), Instant.parse("2026-07-02T07:15:00Z"),
                null, List.of(), null)))));
        mcpFinance.setDispatcher(fixedJson(json.writeValueAsString(List.of(new SpendingByCategoryRow(
                UUID.randomUUID(), "Groceries", "RUB", new BigDecimal("1234.50"), 3)))));
        mcpWeb.setDispatcher(fixedJson(json.writeValueAsString(new WebSearchResult("AI", List.of(
                new WebSearchHit("AI breakthrough", "https://example.com/ai", "A new model shipped."))))));
        mediaService.setDispatcher(fixedJson(json.writeValueAsString(new MediaObjectDto(
                UUID.randomUUID(), householdId, ownerId, "file", "text/html", 4096, "sha", "briefing", Instant.now()))));
        notifier.setDispatcher(ok());
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "Доброе утро! Ваш брифинг на сегодня.", "stop", new LlmUsage(200, 80, 280)))));

        post("briefing.digest", wake(householdId, ownerId));

        // The owner was notified with the composed digest text (no household fan-out lookup).
        RecordedRequest notifyReq = notifier.takeRequest(3, TimeUnit.SECONDS);
        assertThat(notifyReq.getPath()).isEqualTo("/v1/notify");
        String body = notifyReq.getBody().readUtf8();
        assertThat(body).contains(ownerId.toString()).contains("Доброе утро! Ваш брифинг на сегодня.");
        // A personal wake delivers straight to the owner — profile-service is not consulted.
        assertThat(profileService.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void householdDigestWakeFansOutToEveryUser() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();

        // Household-default profile (no ownerId) — agenda + finance gather; no coords/interests.
        mcpBriefing.setDispatcher(fixedJson(json.writeValueAsString(new BriefingProfileDto(
                UUID.randomUUID(), householdId, null, null, null, null, null, null,
                json.readTree("[\"agenda\",\"finance\"]"), null, null, null, Instant.now()))));
        mcpCaldav.setDispatcher(fixedJson(json.writeValueAsString(List.of(new CalendarEventDto(
                UUID.randomUUID(), householdId, "personal", "uid-2", "Dentist", null, null,
                Instant.parse("2026-07-02T09:00:00Z"), Instant.parse("2026-07-02T09:30:00Z"),
                null, List.of(), null)))));
        mcpFinance.setDispatcher(fixedJson(json.writeValueAsString(List.of(new SpendingByCategoryRow(
                UUID.randomUUID(), "Transport", "RUB", new BigDecimal("300.00"), 2)))));
        mediaService.setDispatcher(fixedJson(json.writeValueAsString(new MediaObjectDto(
                UUID.randomUUID(), householdId, null, "file", "text/html", 2048, "sha", "briefing", Instant.now()))));
        profileService.setDispatcher(fixedJson(json.writeValueAsString(List.of(
                new UserDto(u1, householdId, "A", "ru", 1L, "member", Instant.now()),
                new UserDto(u2, householdId, "B", "ru", 2L, "member", Instant.now())))));
        notifier.setDispatcher(ok());
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "Общий брифинг семьи на сегодня.", "stop", new LlmUsage(120, 40, 160)))));

        post("briefing.digest", wake(householdId, null));

        // Both household users were notified.
        RecordedRequest n1 = notifier.takeRequest(3, TimeUnit.SECONDS);
        RecordedRequest n2 = notifier.takeRequest(3, TimeUnit.SECONDS);
        assertThat(n1).isNotNull();
        assertThat(n2).isNotNull();
        String bodies = n1.getBody().readUtf8() + n2.getBody().readUtf8();
        assertThat(bodies).contains(u1.toString()).contains(u2.toString())
                .contains("Общий брифинг семьи на сегодня.");
    }

    @Test
    void unknownTriggerKindReturns404() {
        UUID householdId = UUID.randomUUID();
        http.post().uri("/agents/briefing/triggers/some.other.kind")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(wake(householdId, null))
                .exchange().expectStatus().isNotFound();
    }

    private void post(String kind, AgentWakeRequest req) {
        http.post().uri("/agents/briefing/triggers/" + kind)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(req)
                .exchange().expectStatus().isAccepted();
    }

    private AgentWakeRequest wake(UUID householdId, UUID ownerId) {
        ObjectNode payload = json.createObjectNode();
        if (ownerId != null) {
            payload.put("ownerId", ownerId.toString());
        }
        return new AgentWakeRequest(UUID.randomUUID(), householdId, "briefing", "briefing.digest", payload);
    }

    private static Dispatcher fixedJson(String body) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return jsonResponse(body);
            }
        };
    }

    private static Dispatcher ok() {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(200);
            }
        };
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
