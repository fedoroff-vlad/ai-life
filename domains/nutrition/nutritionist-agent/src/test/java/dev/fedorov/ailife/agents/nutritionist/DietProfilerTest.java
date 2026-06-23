package dev.fedorov.ailife.agents.nutritionist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.nutrition.DietProfileDto;
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
 * Exercises the diet-profiler flow (NU-d) through the agent's HTTP surface ({@code POST
 * /agents/nutritionist/intent}): a typed message with a diet-profile cue → llm-gateway extracts the
 * profile via the {@code diet-profiler} SKILL → upsert via mcp-nutrition's {@code /internal/diet-profile}.
 * MockWebServers stand in for llm-gateway and mcp-nutrition.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DietProfilerTest {

    static MockWebServer mcpNutrition;
    static MockWebServer llmGateway;

    @BeforeAll
    static void start() throws Exception {
        mcpNutrition = new MockWebServer();
        llmGateway = new MockWebServer();
        mcpNutrition.start();
        llmGateway.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpNutrition.shutdown();
        llmGateway.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("nutritionist-agent.mcp-nutrition-url", () -> "http://localhost:" + mcpNutrition.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void typedGoalsAreExtractedAndUpsertedForSelf() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        var draftJson = "{\"scope\": \"self\", \"goal_kcal\": 2000, \"goal_protein_g\": 140, "
                + "\"restrictions\": [\"no-nuts\"], \"notes\": \"cutting\"}";
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", draftJson, "stop", new LlmUsage(30, 15, 45)))));
        mcpNutrition.enqueue(jsonResponse(json.writeValueAsString(new DietProfileDto(
                UUID.randomUUID(), householdId, userId, 2000, new java.math.BigDecimal("140"),
                null, null, json.readTree("[\"no-nuts\"]"), null, "cutting", Instant.now()))));

        var msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "моя цель 2000 ккал, белок 140 г, без орехов", List.of(), "telegram", "90", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("ваш профиль").contains("2000");

        // The extract went through llm-gateway with the SKILL as system prompt + the user text.
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        assertThat(llmReq.getBody().readUtf8())
                .contains("strict JSON")
                .contains("без орехов");

        // The profile was upserted for the sender (self → ownerId = userId).
        RecordedRequest setReq = mcpNutrition.takeRequest(2, TimeUnit.SECONDS);
        assertThat(setReq.getPath()).isEqualTo("/internal/diet-profile");
        JsonNode body = json.readTree(setReq.getBody().readUtf8());
        assertThat(body.path("householdId").asText()).isEqualTo(householdId.toString());
        assertThat(body.path("ownerId").asText()).isEqualTo(userId.toString());
        assertThat(body.path("goalKcal").asInt()).isEqualTo(2000);
        assertThat(body.path("restrictions").get(0).asText()).isEqualTo("no-nuts");
    }

    @Test
    void householdScopeWritesTheDefaultProfile() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{\"scope\": \"household\", \"restrictions\": [\"halal\"]}",
                "stop", new LlmUsage(20, 10, 30)))));
        mcpNutrition.enqueue(jsonResponse(json.writeValueAsString(new DietProfileDto(
                UUID.randomUUID(), householdId, null, null, null, null, null,
                json.readTree("[\"halal\"]"), null, null, Instant.now()))));

        var msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "у нас в семье халяль, мои ограничения важны", List.of(), "telegram", "91", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("семьи");

        llmGateway.takeRequest(2, TimeUnit.SECONDS);
        // household scope → ownerId omitted (null = household-default).
        RecordedRequest setReq = mcpNutrition.takeRequest(2, TimeUnit.SECONDS);
        JsonNode body = json.readTree(setReq.getBody().readUtf8());
        assertThat(body.has("ownerId")).isFalse();   // NON_NULL → absent
        assertThat(body.path("restrictions").get(0).asText()).isEqualTo("halal");
    }

    @Test
    void notAProfileRepliesWithoutWriting() throws Exception {
        UUID householdId = UUID.randomUUID();

        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{\"error\": \"not a profile\"}", "stop", new LlmUsage(10, 5, 15)))));

        var msg = new NormalizedMessage(UUID.randomUUID(), householdId, MessageScope.PRIVATE,
                "у меня аллергия — что вообще можно есть?", List.of(), "telegram", "92", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Не понял");

        llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(mcpNutrition.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    private IntentResponse post(NormalizedMessage msg) {
        return http.post().uri("/agents/nutritionist/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
