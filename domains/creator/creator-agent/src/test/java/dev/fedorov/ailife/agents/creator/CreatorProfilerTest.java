package dev.fedorov.ailife.agents.creator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.creator.CreatorProfileDto;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the creator-profiler flow (CR-c) through the agent's HTTP surface ({@code POST
 * /agents/creator/intent}): a typed message with a creator-profile cue → llm-gateway extracts the
 * track via the {@code creator-profiler} SKILL → upsert via mcp-creator's {@code /internal/creator-profile}.
 * MockWebServers stand in for llm-gateway and mcp-creator.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class CreatorProfilerTest {

    static MockWebServer mcpCreator;
    static MockWebServer llmGateway;

    @BeforeAll
    static void start() throws Exception {
        mcpCreator = new MockWebServer();
        llmGateway = new MockWebServer();
        mcpCreator.start();
        llmGateway.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpCreator.shutdown();
        llmGateway.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("creator-agent.mcp-creator-url", () -> "http://localhost:" + mcpCreator.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void typedProfileIsExtractedAndUpsertedForSelf() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        var draftJson = "{\"scope\": \"self\", \"niche\": \"English for IT\", \"audience\": \"junior devs\", "
                + "\"tone\": \"friendly-expert\", \"platforms\": [\"youtube\", \"reddit\"], "
                + "\"guardrails\": {\"noClickbait\": true}, \"notes\": \"ru/en\"}";
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", draftJson, "stop", new LlmUsage(30, 15, 45)))));
        mcpCreator.enqueue(jsonResponse(json.writeValueAsString(new CreatorProfileDto(
                UUID.randomUUID(), householdId, userId, "English for IT", "junior devs",
                "friendly-expert", json.readTree("[\"youtube\",\"reddit\"]"), null,
                json.readTree("{\"noClickbait\":true}"), "ru/en", Instant.now()))));

        var msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "моя ниша — английский для IT, аудитория джуны, тон дружелюбный",
                List.of(), "telegram", "90", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("ваш профиль").contains("English for IT");

        // The extract went through llm-gateway with the SKILL as system prompt + the user text.
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        assertThat(llmReq.getBody().readUtf8())
                .contains("strict JSON")
                .contains("английский для IT");

        // The track was upserted for the sender (self → ownerId = userId).
        RecordedRequest setReq = mcpCreator.takeRequest(2, TimeUnit.SECONDS);
        assertThat(setReq.getPath()).isEqualTo("/internal/creator-profile");
        JsonNode body = json.readTree(setReq.getBody().readUtf8());
        assertThat(body.path("householdId").asText()).isEqualTo(householdId.toString());
        assertThat(body.path("ownerId").asText()).isEqualTo(userId.toString());
        assertThat(body.path("niche").asText()).isEqualTo("English for IT");
        assertThat(body.path("platforms").get(0).asText()).isEqualTo("youtube");
    }

    @Test
    void householdScopeWritesTheDefaultTrack() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{\"scope\": \"household\", \"niche\": \"family brand\"}",
                "stop", new LlmUsage(20, 10, 30)))));
        mcpCreator.enqueue(jsonResponse(json.writeValueAsString(new CreatorProfileDto(
                UUID.randomUUID(), householdId, null, "family brand", null, null, null, null,
                null, null, Instant.now()))));

        var msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "наш общий канал — ниша семейный бренд, мой контент про это", List.of(), "telegram", "91", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("общий профиль");

        llmGateway.takeRequest(2, TimeUnit.SECONDS);
        // household scope → ownerId omitted (null = household-default).
        RecordedRequest setReq = mcpCreator.takeRequest(2, TimeUnit.SECONDS);
        JsonNode body = json.readTree(setReq.getBody().readUtf8());
        assertThat(body.has("ownerId")).isFalse();   // NON_NULL → absent
        assertThat(body.path("niche").asText()).isEqualTo("family brand");
    }

    @Test
    void notAProfileRepliesWithoutWriting() throws Exception {
        UUID householdId = UUID.randomUUID();

        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{\"error\": \"not a profile\"}", "stop", new LlmUsage(10, 5, 15)))));

        // A profile cue routes here, but the SKILL decides it isn't actually a profile.
        var msg = new NormalizedMessage(UUID.randomUUID(), householdId, MessageScope.PRIVATE,
                "мой контент не заходит — что делать?", List.of(), "telegram", "92", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Не понял");

        llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(mcpCreator.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    private IntentResponse post(NormalizedMessage msg) {
        return http.post().uri("/agents/creator/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
