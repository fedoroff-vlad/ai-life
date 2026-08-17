package dev.fedorov.ailife.agents.travel;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.travel.TravelProfileDto;
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
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
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
 * Exercises the travel-profiler flow (TR-c) through the agent's HTTP surface ({@code POST
 * /agents/travel/intent}): a typed message with a preferences cue → llm-gateway extracts the prefs via
 * the {@code travel-profiler} SKILL → mcp-weather geocodes the stated home-base city → upsert via
 * mcp-travel's {@code /internal/travel-profile}. MockWebServers stand in for llm-gateway, mcp-weather,
 * and mcp-travel.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class TravelProfilerTest {

    static MockWebServer mcpTravel;
    static MockWebServer mcpWeather;
    static MockWebServer llmGateway;

    @BeforeAll
    static void start() throws Exception {
        mcpTravel = new MockWebServer();
        mcpWeather = new MockWebServer();
        llmGateway = new MockWebServer();
        mcpTravel.start();
        mcpWeather.start();
        llmGateway.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpTravel.shutdown();
        mcpWeather.shutdown();
        llmGateway.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("travel-agent.mcp-travel-url", () -> "http://localhost:" + mcpTravel.getPort());
        r.add("travel-agent.mcp-weather-url", () -> "http://localhost:" + mcpWeather.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void typedPrefsAreExtractedGeocodedAndUpsertedForSelf() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        String draftJson = "{\"scope\":\"self\",\"homeBase\":\"Москва\",\"restTypes\":[\"beach\"],"
                + "\"companions\":\"family\",\"childAges\":[4],\"budgetAmount\":200000,\"budgetCurrency\":\"RUB\"}";
        // Two LLM turns now (#475): the TravelIntentRouter classifies (→ travel-profiler), then the extract.
        enqueuePick("travel-profiler");
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", draftJson, "stop", new LlmUsage(40, 20, 60)))));
        mcpWeather.enqueue(jsonResponse(json.writeValueAsString(
                new GeoLocation("Moscow", "Russia", 55.75, 37.62, "Europe/Moscow"))));
        mcpTravel.enqueue(jsonResponse(json.writeValueAsString(new TravelProfileDto(
                UUID.randomUUID(), householdId, userId, "Москва", 55.75, 37.62,
                json.readTree("[\"beach\"]"), "family", json.readTree("[4]"),
                new BigDecimal("200000.00"), "RUB", null, Instant.now()))));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "любим пляж, семья с ребёнком 4 года, летаем из Москвы, бюджет тысяч 200",
                List.of(), "telegram", "80", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("ваши настройки").contains("Москва");

        // First the router classify, then the extract went through llm-gateway with the SKILL as system prompt.
        llmGateway.takeRequest(2, TimeUnit.SECONDS);   // router classify
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        assertThat(llmReq.getBody().readUtf8()).contains("strict JSON").contains("Москв");

        // The home-base city was geocoded via mcp-weather.
        RecordedRequest geoReq = mcpWeather.takeRequest(2, TimeUnit.SECONDS);
        assertThat(geoReq.getPath()).isEqualTo("/internal/geocode");
        assertThat(geoReq.getBody().readUtf8()).contains("Москва");

        // The prefs were upserted for the sender (self → ownerId = userId), with the geocoded coords.
        RecordedRequest setReq = mcpTravel.takeRequest(2, TimeUnit.SECONDS);
        assertThat(setReq.getPath()).isEqualTo("/internal/travel-profile");
        JsonNode body = json.readTree(setReq.getBody().readUtf8());
        assertThat(body.path("householdId").asString()).isEqualTo(householdId.toString());
        assertThat(body.path("ownerId").asString()).isEqualTo(userId.toString());
        assertThat(body.path("homeBaseLabel").asString()).isEqualTo("Москва");
        assertThat(body.path("homeBaseLatitude").asDouble()).isEqualTo(55.75);
        assertThat(body.path("restTypes").get(0).asString()).isEqualTo("beach");
        assertThat(body.path("companions").asString()).isEqualTo("family");
        assertThat(body.path("childAges").get(0).asInt()).isEqualTo(4);
        assertThat(body.path("budgetAmount").asDouble()).isEqualTo(200000);
        assertThat(body.path("budgetCurrency").asString()).isEqualTo("RUB");
    }

    @Test
    void outOfVocabularyRestTypeAndCompanionsAreDropped() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // The model returns one valid + one invalid rest type, and an invalid companions value.
        String draftJson = "{\"scope\":\"self\",\"homeBase\":\"Berlin\","
                + "\"restTypes\":[\"beach\",\"safari\"],\"companions\":\"squad\"}";
        enqueuePick("travel-profiler");                // router classify → travel-profiler
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", draftJson, "stop", new LlmUsage(30, 15, 45)))));
        mcpWeather.enqueue(jsonResponse(json.writeValueAsString(
                new GeoLocation("Berlin", "Germany", 52.52, 13.41, "Europe/Berlin"))));
        mcpTravel.enqueue(jsonResponse(json.writeValueAsString(new TravelProfileDto(
                UUID.randomUUID(), householdId, userId, "Berlin", 52.52, 13.41,
                json.readTree("[\"beach\"]"), null, null, null, null, null, Instant.now()))));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "мои предпочтения для путешествий: люблю пляж и сафари, летаю из Berlin",
                List.of(), "telegram", "81", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();

        llmGateway.takeRequest(2, TimeUnit.SECONDS);   // router classify
        llmGateway.takeRequest(2, TimeUnit.SECONDS);   // extract
        mcpWeather.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest setReq = mcpTravel.takeRequest(2, TimeUnit.SECONDS);
        JsonNode body = json.readTree(setReq.getBody().readUtf8());
        // Invalid "safari" dropped — only the valid kind survives; invalid companions omitted entirely.
        assertThat(body.path("restTypes")).hasSize(1);
        assertThat(body.path("restTypes").get(0).asString()).isEqualTo("beach");
        assertThat(body.has("companions")).isFalse();   // NON_NULL → absent
    }

    @Test
    void householdScopeWritesTheDefaultTrackAndNoHomeBaseSkipsGeocode() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        enqueuePick("travel-profiler");                // router classify → travel-profiler
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{\"scope\":\"household\",\"restTypes\":[\"family\",\"beach\"],\"companions\":\"family\"}",
                "stop", new LlmUsage(20, 10, 30)))));
        mcpTravel.enqueue(jsonResponse(json.writeValueAsString(new TravelProfileDto(
                UUID.randomUUID(), householdId, null, null, null, null,
                json.readTree("[\"family\",\"beach\"]"), "family", null, null, null, null, Instant.now()))));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "наши семейные настройки путешествий: любим семейный пляжный отдых", List.of(), "telegram", "82", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("общие настройки");

        llmGateway.takeRequest(2, TimeUnit.SECONDS);   // router classify
        llmGateway.takeRequest(2, TimeUnit.SECONDS);   // extract
        // No home base stated → geocode is skipped entirely.
        assertThat(mcpWeather.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
        // household scope → ownerId omitted (null = household-default).
        RecordedRequest setReq = mcpTravel.takeRequest(2, TimeUnit.SECONDS);
        JsonNode body = json.readTree(setReq.getBody().readUtf8());
        assertThat(body.has("ownerId")).isFalse();
        assertThat(body.path("restTypes")).hasSize(2);
    }

    @Test
    void notATravelProfileRepliesWithoutWriting() throws Exception {
        UUID householdId = UUID.randomUUID();

        enqueuePick("travel-profiler");                // router classify → travel-profiler
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{\"error\": \"not a travel profile\"}", "stop", new LlmUsage(10, 5, 15)))));

        NormalizedMessage msg = new NormalizedMessage(UUID.randomUUID(), householdId, MessageScope.PRIVATE,
                "мои предпочтения в кино — фантастика", List.of(), "telegram", "83", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Не понял");

        llmGateway.takeRequest(2, TimeUnit.SECONDS);   // router classify
        llmGateway.takeRequest(2, TimeUnit.SECONDS);   // extract (→ error, no write)
        assertThat(mcpWeather.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(mcpTravel.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void geocodeSoftFailStillUpsertsWithoutCoordinates() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        enqueuePick("travel-profiler");                // router classify → travel-profiler
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{\"scope\":\"self\",\"homeBase\":\"Нигдетаун\",\"restTypes\":[\"city\"]}",
                "stop", new LlmUsage(15, 8, 23)))));
        // Geocoding errors (500) → the client soft-fails to empty → profile saved without coords.
        mcpWeather.enqueue(new MockResponse().setResponseCode(500));
        mcpTravel.enqueue(jsonResponse(json.writeValueAsString(new TravelProfileDto(
                UUID.randomUUID(), householdId, userId, "Нигдетаун", null, null,
                json.readTree("[\"city\"]"), null, null, null, null, null, Instant.now()))));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "мои настройки путешествий: город вылета Нигдетаун, люблю city отдых", List.of(), "telegram", "84", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("координаты");   // warns coords couldn't be resolved

        llmGateway.takeRequest(2, TimeUnit.SECONDS);   // router classify
        llmGateway.takeRequest(2, TimeUnit.SECONDS);   // extract
        mcpWeather.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest setReq = mcpTravel.takeRequest(2, TimeUnit.SECONDS);
        JsonNode body = json.readTree(setReq.getBody().readUtf8());
        assertThat(body.path("homeBaseLabel").asString()).isEqualTo("Нигдетаун");
        assertThat(body.has("homeBaseLatitude")).isFalse();   // NON_NULL → absent (no coords)
    }

    private IntentResponse post(NormalizedMessage msg) {
        return http.post().uri("/agents/travel/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();
    }

    /** The TravelIntentRouter's classify turn (#475) that routes the text to {@code skill} before its flow runs. */
    private void enqueuePick(String skill) throws Exception {
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{\"action\":\"skill\",\"name\":\"" + skill + "\"}", "stop",
                new LlmUsage(20, 8, 28)))));
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
