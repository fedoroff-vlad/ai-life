package dev.fedorov.ailife.agents.briefing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.briefing.BriefingProfileDto;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.weather.GeoLocation;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the briefing-profiler flow (BR-c) through the agent's HTTP surface ({@code POST
 * /agents/briefing/intent}): a typed message with a preferences cue → llm-gateway extracts the prefs
 * via the {@code briefing-profiler} SKILL → mcp-weather geocodes the stated city → upsert via
 * mcp-briefing's {@code /internal/briefing-profile}. MockWebServers stand in for llm-gateway,
 * mcp-weather, and mcp-briefing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BriefingProfilerTest {

    static MockWebServer mcpBriefing;
    static MockWebServer mcpWeather;
    static MockWebServer llmGateway;

    @BeforeAll
    static void start() throws Exception {
        mcpBriefing = new MockWebServer();
        mcpWeather = new MockWebServer();
        llmGateway = new MockWebServer();
        mcpBriefing.start();
        mcpWeather.start();
        llmGateway.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpBriefing.shutdown();
        mcpWeather.shutdown();
        llmGateway.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("briefing-agent.mcp-briefing-url", () -> "http://localhost:" + mcpBriefing.getPort());
        r.add("briefing-agent.mcp-weather-url", () -> "http://localhost:" + mcpWeather.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void typedPrefsAreExtractedGeocodedAndUpsertedForSelf() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        String draftJson = "{\"scope\":\"self\",\"location\":\"Москва\",\"interests\":[\"AI\",\"finance\"],"
                + "\"sections\":[\"weather\",\"agenda\",\"finance\",\"news\"],\"scheduleTime\":\"08:00\","
                + "\"scheduleEnabled\":true}";
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", draftJson, "stop", new LlmUsage(40, 20, 60)))));
        mcpWeather.enqueue(jsonResponse(json.writeValueAsString(
                new GeoLocation("Moscow", "Russia", 55.75, 37.62, "Europe/Moscow"))));
        mcpBriefing.enqueue(jsonResponse(json.writeValueAsString(new BriefingProfileDto(
                UUID.randomUUID(), householdId, userId, "Москва", 55.75, 37.62, "Europe/Moscow",
                json.readTree("[\"AI\",\"finance\"]"), json.readTree("[\"weather\",\"agenda\",\"finance\",\"news\"]"),
                "08:00", true, null, Instant.now()))));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "каждое утро в 8:00 показывай погоду в Москве, новости про ИИ и финансы",
                List.of(), "telegram", "70", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("ваши настройки").contains("Москва");

        // The extract went through llm-gateway with the SKILL as system prompt + the user text.
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        assertThat(llmReq.getBody().readUtf8()).contains("strict JSON").contains("Москве");

        // The city was geocoded via mcp-weather.
        RecordedRequest geoReq = mcpWeather.takeRequest(2, TimeUnit.SECONDS);
        assertThat(geoReq.getPath()).isEqualTo("/internal/geocode");
        assertThat(geoReq.getBody().readUtf8()).contains("Москва");

        // The prefs were upserted for the sender (self → ownerId = userId), with the geocoded coords.
        RecordedRequest setReq = mcpBriefing.takeRequest(2, TimeUnit.SECONDS);
        assertThat(setReq.getPath()).isEqualTo("/internal/briefing-profile");
        JsonNode body = json.readTree(setReq.getBody().readUtf8());
        assertThat(body.path("householdId").asText()).isEqualTo(householdId.toString());
        assertThat(body.path("ownerId").asText()).isEqualTo(userId.toString());
        assertThat(body.path("locationLabel").asText()).isEqualTo("Москва");
        assertThat(body.path("latitude").asDouble()).isEqualTo(55.75);
        assertThat(body.path("timezone").asText()).isEqualTo("Europe/Moscow");
        assertThat(body.path("interests").get(0).asText()).isEqualTo("AI");
        assertThat(body.path("sections")).hasSize(4);
        assertThat(body.path("scheduleTime").asText()).isEqualTo("08:00");
        assertThat(body.path("scheduleEnabled").asBoolean()).isTrue();
    }

    @Test
    void householdScopeWritesTheDefaultTrackAndNoLocationSkipsGeocode() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{\"scope\":\"household\",\"sections\":[\"weather\",\"news\"],\"scheduleEnabled\":true}",
                "stop", new LlmUsage(20, 10, 30)))));
        mcpBriefing.enqueue(jsonResponse(json.writeValueAsString(new BriefingProfileDto(
                UUID.randomUUID(), householdId, null, null, null, null, null, null,
                json.readTree("[\"weather\",\"news\"]"), null, true, null, Instant.now()))));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "наш общий брифинг: показывай каждое утро погоду и новости", List.of(), "telegram", "71", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("общие настройки");

        llmGateway.takeRequest(2, TimeUnit.SECONDS);
        // No location stated → geocode is skipped entirely.
        assertThat(mcpWeather.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
        // household scope → ownerId omitted (null = household-default).
        RecordedRequest setReq = mcpBriefing.takeRequest(2, TimeUnit.SECONDS);
        JsonNode body = json.readTree(setReq.getBody().readUtf8());
        assertThat(body.has("ownerId")).isFalse();   // NON_NULL → absent
        assertThat(body.path("sections").get(0).asText()).isEqualTo("weather");
    }

    @Test
    void notABriefingProfileRepliesWithoutWriting() throws Exception {
        UUID householdId = UUID.randomUUID();

        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{\"error\": \"not a briefing profile\"}", "stop", new LlmUsage(10, 5, 15)))));

        // A cue ("по утрам") routes here, but the SKILL decides it isn't actually a briefing config.
        NormalizedMessage msg = new NormalizedMessage(UUID.randomUUID(), householdId, MessageScope.PRIVATE,
                "по утрам я бегаю в парке", List.of(), "telegram", "72", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Не понял");

        llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(mcpWeather.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(mcpBriefing.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void geocodeSoftFailStillUpsertsWithoutCoordinates() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{\"scope\":\"self\",\"location\":\"Нигдетаун\",\"scheduleEnabled\":true}",
                "stop", new LlmUsage(15, 8, 23)))));
        // Geocoding errors (500) → the client soft-fails to empty → profile saved without coords.
        mcpWeather.enqueue(new MockResponse().setResponseCode(500));
        mcpBriefing.enqueue(jsonResponse(json.writeValueAsString(new BriefingProfileDto(
                UUID.randomUUID(), householdId, userId, "Нигдетаун", null, null, null, null, null,
                null, true, null, Instant.now()))));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "настрой брифинг для города Нигдетаун", List.of(), "telegram", "73", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("координаты");   // warns coords couldn't be resolved

        llmGateway.takeRequest(2, TimeUnit.SECONDS);
        mcpWeather.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest setReq = mcpBriefing.takeRequest(2, TimeUnit.SECONDS);
        JsonNode body = json.readTree(setReq.getBody().readUtf8());
        assertThat(body.path("locationLabel").asText()).isEqualTo("Нигдетаун");
        assertThat(body.has("latitude")).isFalse();   // NON_NULL → absent (no coords)
    }

    private IntentResponse post(NormalizedMessage msg) {
        return http.post().uri("/agents/briefing/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
