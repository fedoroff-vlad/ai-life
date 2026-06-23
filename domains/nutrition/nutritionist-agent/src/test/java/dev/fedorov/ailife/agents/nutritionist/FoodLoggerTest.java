package dev.fedorov.ailife.agents.nutritionist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.Attachment;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.media.CaptionResult;
import dev.fedorov.ailife.contracts.nutrition.MealLogDto;
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
 * Exercises the food-log flow (NU-c) through the agent's HTTP surface ({@code POST
 * /agents/nutritionist/intent}). A meal photo asks the shared mcp-media-processing {@code caption}
 * passthrough for a structured extract; a typed meal asks llm-gateway directly — both via the
 * {@code meal-logger} SKILL — then write to mcp-nutrition's {@code /internal/meal}. Write-immediately
 * (no confirm). MockWebServers stand in for the three capabilities.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FoodLoggerTest {

    static MockWebServer mediaProcessing;
    static MockWebServer mcpNutrition;
    static MockWebServer llmGateway;

    @BeforeAll
    static void start() throws Exception {
        mediaProcessing = new MockWebServer();
        mcpNutrition = new MockWebServer();
        llmGateway = new MockWebServer();
        mediaProcessing.start();
        mcpNutrition.start();
        llmGateway.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mediaProcessing.shutdown();
        mcpNutrition.shutdown();
        llmGateway.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("nutritionist-agent.mcp-media-processing-url", () -> "http://localhost:" + mediaProcessing.getPort());
        r.add("nutritionist-agent.mcp-nutrition-url", () -> "http://localhost:" + mcpNutrition.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void photoMealIsCaptionedThenLogged() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String mediaId = UUID.randomUUID().toString();

        var draftJson = "{\"description\": \"куриный салат\", \"items\": [{\"name\": \"курица\", "
                + "\"qty\": \"150 г\"}], \"kcal\": 350, \"protein_g\": 30, \"fat_g\": 12, \"carbs_g\": 10}";
        mediaProcessing.enqueue(jsonResponse(json.writeValueAsString(new CaptionResult(draftJson, "mock-vision"))));
        mcpNutrition.enqueue(jsonResponse(json.writeValueAsString(new MealLogDto(
                UUID.randomUUID(), householdId, userId, Instant.now(), "photo", "куриный салат",
                json.readTree("[{\"name\":\"курица\"}]"), 350, new BigDecimal("30"), new BigDecimal("12"),
                new BigDecimal("10"), UUID.fromString(mediaId), Instant.now()))));

        var msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE, "это мой обед",
                List.of(new Attachment("image", "image/jpeg", mediaId, null)),
                "telegram", "80", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Записал").contains("куриный салат");
        assertThat(resp.pendingAction()).isNull(); // write-immediately, no confirm lock

        // The caption passthrough got the media id + the SKILL instruction + the user note.
        RecordedRequest captionReq = mediaProcessing.takeRequest(2, TimeUnit.SECONDS);
        assertThat(captionReq.getPath()).isEqualTo("/internal/caption");
        JsonNode captionBody = json.readTree(captionReq.getBody().readUtf8());
        assertThat(captionBody.path("mediaId").asText()).isEqualTo(mediaId);
        assertThat(captionBody.path("instruction").asText())
                .contains("strict JSON")   // the meal-logger SKILL.md prompt
                .contains("это мой обед");  // the user's caption folded in as a hint

        // The meal was written with the parsed fields, source=photo, the sender as owner + the media id.
        RecordedRequest mealReq = mcpNutrition.takeRequest(2, TimeUnit.SECONDS);
        assertThat(mealReq.getPath()).isEqualTo("/internal/meal");
        JsonNode mealBody = json.readTree(mealReq.getBody().readUtf8());
        assertThat(mealBody.path("householdId").asText()).isEqualTo(householdId.toString());
        assertThat(mealBody.path("ownerId").asText()).isEqualTo(userId.toString());
        assertThat(mealBody.path("description").asText()).isEqualTo("куриный салат");
        assertThat(mealBody.path("source").asText()).isEqualTo("photo");
        assertThat(mealBody.path("kcal").asInt()).isEqualTo(350);
        assertThat(mealBody.path("imageMediaId").asText()).isEqualTo(mediaId);
    }

    @Test
    void typedMealIsExtractedThenLogged() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        var draftJson = "{\"description\": \"овсянка с бананом\", \"kcal\": 420, \"protein_g\": 12}";
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", draftJson, "stop", new LlmUsage(30, 15, 45)))));
        mcpNutrition.enqueue(jsonResponse(json.writeValueAsString(new MealLogDto(
                UUID.randomUUID(), householdId, userId, Instant.now(), "text", "овсянка с бананом",
                null, 420, new BigDecimal("12"), null, null, null, Instant.now()))));

        var msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "на завтрак съел овсянку с бананом", List.of(), "telegram", "81", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Записал").contains("овсянка");

        // The extract went through llm-gateway with the SKILL as system prompt + the user text.
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        assertThat(llmReq.getBody().readUtf8())
                .contains("strict JSON")
                .contains("овсянку с бананом");

        // The meal was written with source=text.
        RecordedRequest mealReq = mcpNutrition.takeRequest(2, TimeUnit.SECONDS);
        JsonNode mealBody = json.readTree(mealReq.getBody().readUtf8());
        assertThat(mealBody.path("source").asText()).isEqualTo("text");
        assertThat(mealBody.path("description").asText()).isEqualTo("овсянка с бананом");
    }

    @Test
    void notAMealRepliesWithoutWriting() throws Exception {
        UUID householdId = UUID.randomUUID();
        String mediaId = UUID.randomUUID().toString();

        mediaProcessing.enqueue(jsonResponse(json.writeValueAsString(
                new CaptionResult("{\"error\": \"not a meal\"}", "mock-vision"))));

        var msg = new NormalizedMessage(UUID.randomUUID(), householdId, MessageScope.PRIVATE, null,
                List.of(new Attachment("image", "image/jpeg", mediaId, null)),
                "telegram", "82", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Не понял");

        // Caption was attempted; no meal write happened.
        mediaProcessing.takeRequest(2, TimeUnit.SECONDS);
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
