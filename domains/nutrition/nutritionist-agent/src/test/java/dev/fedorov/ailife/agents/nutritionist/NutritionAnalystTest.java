package dev.fedorov.ailife.agents.nutritionist;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.contracts.nutrition.DietProfileDto;
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
 * Exercises the nutrition-analysis flow (NU-e) through the agent's HTTP surface ({@code POST
 * /agents/nutritionist/intent}). An analysis-cue message gathers the person's recent meals
 * ({@code GET /internal/meals}) + diet profile ({@code GET /internal/diet-profile}) from
 * mcp-nutrition, synthesises one analysis via llm-gateway (the {@code nutrition-analyst} SKILL),
 * renders it to HTML via the shared doc-render, stores it in media-service, and replies with a link.
 * An empty food log → an invite, no LLM/store calls. MockWebServers stand in for the capabilities.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NutritionAnalystTest {

    static MockWebServer mcpNutrition;
    static MockWebServer llmGateway;
    static MockWebServer mediaService;

    @BeforeAll
    static void start() throws Exception {
        mcpNutrition = new MockWebServer();
        llmGateway = new MockWebServer();
        mediaService = new MockWebServer();
        mcpNutrition.start();
        llmGateway.start();
        mediaService.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpNutrition.shutdown();
        llmGateway.shutdown();
        mediaService.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("nutritionist-agent.mcp-nutrition-url", () -> "http://localhost:" + mcpNutrition.getPort());
        r.add("nutritionist-agent.media-service-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("nutritionist-agent.public-media-base-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void analysisGathersMealsAndProfileThenRendersAndLinks() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID storedId = UUID.randomUUID();

        // GET /internal/meals → two logged meals.
        mcpNutrition.enqueue(jsonResponse(json.writeValueAsString(List.of(
                new MealLogDto(UUID.randomUUID(), householdId, userId, Instant.now(), "text",
                        "овсянка с бананом", null, 420, new BigDecimal("12"), new BigDecimal("8"),
                        new BigDecimal("70"), null, Instant.now()),
                new MealLogDto(UUID.randomUUID(), householdId, userId, Instant.now(), "photo",
                        "куриный салат", null, 350, new BigDecimal("30"), new BigDecimal("12"),
                        new BigDecimal("10"), null, Instant.now())))));
        // GET /internal/diet-profile → a goal profile.
        mcpNutrition.enqueue(jsonResponse(json.writeValueAsString(new DietProfileDto(
                UUID.randomUUID(), householdId, userId, 2000, new BigDecimal("140"), null, null,
                json.readTree("[\"no-nuts\"]"), null, "cutting", Instant.now()))));
        // llm-gateway synthesis.
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large",
                "Питаетесь сбалансированно, но белка маловато.\nДобавьте источник белка на ужин.",
                "stop", new LlmUsage(120, 60, 180)))));
        // media-service stores the rendered HTML.
        mediaService.enqueue(jsonResponse(json.writeValueAsString(new MediaObjectDto(
                storedId, householdId, userId, "file", "text/html", 2048, "sha", "nutritionist",
                Instant.now()))));

        var msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "сделай разбор питания", List.of(), "telegram", "90", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Полный разбор").contains(storedId.toString());

        // meals were gathered for the sender.
        RecordedRequest mealsReq = mcpNutrition.takeRequest(2, TimeUnit.SECONDS);
        assertThat(mealsReq.getPath()).startsWith("/internal/meals");
        assertThat(mealsReq.getPath()).contains("householdId=" + householdId).contains("ownerId=" + userId);

        // the profile was gathered.
        RecordedRequest profileReq = mcpNutrition.takeRequest(2, TimeUnit.SECONDS);
        assertThat(profileReq.getPath()).startsWith("/internal/diet-profile");

        // the synthesis carried the SKILL + the gathered meals + profile in the context.
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        String llmBody = llmReq.getBody().readUtf8();
        assertThat(llmBody)
                .contains("nutrition")          // the nutrition-analyst SKILL prompt
                .contains("овсянка с бананом")  // gathered meals folded into context
                .contains("2000");              // gathered profile goal

        // the rendered HTML page was uploaded.
        RecordedRequest mediaReq = mediaService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(mediaReq.getPath()).isEqualTo("/v1/media");
        String mediaBody = mediaReq.getBody().readUtf8();
        assertThat(mediaBody).contains("Разбор питания");   // the rendered board title
    }

    @Test
    void emptyLogInvitesToLogFirstWithoutSynthesis() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // GET /internal/meals → empty log.
        mcpNutrition.enqueue(jsonResponse("[]"));

        var msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "как я питаюсь?", List.of(), "telegram", "91", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("дневник").contains("пусто");

        // only the meals fetch happened — no profile gather, no LLM, no store.
        RecordedRequest mealsReq = mcpNutrition.takeRequest(2, TimeUnit.SECONDS);
        assertThat(mealsReq.getPath()).startsWith("/internal/meals");
        assertThat(mcpNutrition.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(llmGateway.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(mediaService.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
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
