package dev.fedorov.ailife.agents.nutritionist;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.Attachment;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.media.CaptionResult;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.contracts.nutrition.BasketDto;
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
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the basket-breakdown flow (NU-f, direct) through the agent's HTTP surface. A basket photo
 * with a basket cue asks the shared mcp-media-processing {@code caption} passthrough for a structured
 * extract+breakdown; a typed list asks llm-gateway directly — both via the {@code basket-analyst}
 * SKILL with the diet profile folded in. The parsed basket is saved to mcp-nutrition's
 * {@code /internal/basket} and the verdict board is rendered to media-service. MockWebServers stand
 * in for the capabilities.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class BasketBreakdownTest {

    static MockWebServer mcpNutrition;
    static MockWebServer mediaProcessing;
    static MockWebServer llmGateway;
    static MockWebServer mediaService;
    static MockWebServer mcpFoodData;

    @BeforeAll
    static void start() throws Exception {
        mcpNutrition = new MockWebServer();
        mediaProcessing = new MockWebServer();
        llmGateway = new MockWebServer();
        mediaService = new MockWebServer();
        mcpFoodData = new MockWebServer();
        mcpNutrition.start();
        mediaProcessing.start();
        llmGateway.start();
        mediaService.start();
        mcpFoodData.start();
        // FD-c: mcp-food-data answers any /internal/food-lookup with a matched product (a fixed
        // FoodFacts) so the basket-breakdown enrichment grounds the board in precise per-100g КБЖУ.
        mcpFoodData.setDispatcher(FOOD_LOOKUP_DISPATCHER);
    }

    @AfterAll
    static void stop() throws Exception {
        mcpNutrition.shutdown();
        mediaProcessing.shutdown();
        llmGateway.shutdown();
        mediaService.shutdown();
        mcpFoodData.shutdown();
    }

    /** Drain any recorded requests so the static servers stay isolated across (unordered) tests. */
    @AfterEach
    void drain() throws Exception {
        for (MockWebServer s : List.of(mcpNutrition, mediaProcessing, llmGateway, mediaService, mcpFoodData)) {
            while (s.takeRequest(50, TimeUnit.MILLISECONDS) != null) {
                // discard
            }
        }
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("nutritionist-agent.mcp-nutrition-url", () -> "http://localhost:" + mcpNutrition.getPort());
        r.add("nutritionist-agent.mcp-media-processing-url", () -> "http://localhost:" + mediaProcessing.getPort());
        r.add("nutritionist-agent.media-service-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("nutritionist-agent.public-media-base-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("nutritionist-agent.mcp-food-data-url", () -> "http://localhost:" + mcpFoodData.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    /** Every food-lookup → a matched product with macros + a Nutri-Score (the FD-c facts section). */
    private static final Dispatcher FOOD_LOOKUP_DISPATCHER = new Dispatcher() {
        @Override
        public MockResponse dispatch(RecordedRequest request) {
            return new MockResponse().setHeader("content-type", "application/json").setBody(
                    "{\"name\":\"Молоко 3.2%\",\"brand\":\"Простоквашино\",\"quantity\":\"1 л\","
                    + "\"nutriScore\":\"b\",\"kcal100g\":60,\"protein100g\":3.0,\"fat100g\":3.2,\"carbs100g\":4.7}");
        }
    };

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    private static final String DRAFT =
            "{\"merchant\":\"Лента\","
            + "\"items\":[{\"name\":\"молоко\",\"qty\":\"1 л\",\"kcal\":60,\"protein_g\":3,\"fat_g\":3.2,\"carbs_g\":4.7},"
            + "{\"name\":\"чипсы\",\"qty\":\"1 шт\",\"kcal\":500}],"
            + "\"totals\":{\"kcal\":560,\"protein_g\":3,\"fat_g\":3.2,\"carbs_g\":4.7},"
            + "\"analysis\":{\"good\":[{\"name\":\"молоко\",\"reason\":\"белок и кальций\"}],"
            + "\"watch\":[],\"cut\":[{\"name\":\"чипсы\",\"reason\":\"ультра-обработанное\"}]},"
            + "\"summary\":\"Корзина в целом ок, убрать чипсы.\"}";

    @Test
    void basketPhotoIsCaptionedBrokenDownSavedAndReported() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String mediaId = UUID.randomUUID().toString();
        UUID storedId = UUID.randomUUID();

        // GET /internal/diet-profile → a profile with a restriction (folded into the instruction).
        mcpNutrition.enqueue(jsonResponse(json.writeValueAsString(
                new dev.fedorov.ailife.contracts.nutrition.DietProfileDto(
                        UUID.randomUUID(), householdId, userId, 2000, null, null, null,
                        json.readTree("[\"no-nuts\"]"), null, "cutting", Instant.now()))));
        // caption extract+breakdown.
        mediaProcessing.enqueue(jsonResponse(json.writeValueAsString(new CaptionResult(DRAFT, "mock-vision"))));
        // POST /internal/basket echo.
        mcpNutrition.enqueue(jsonResponse(json.writeValueAsString(basketEcho(householdId, userId, mediaId))));
        // media-service stores the board.
        mediaService.enqueue(jsonResponse(json.writeValueAsString(new MediaObjectDto(
                storedId, householdId, userId, "file", "text/html", 4096, "sha", "nutritionist", Instant.now()))));

        var msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE, "разбери корзину",
                List.of(new Attachment("image", "image/jpeg", mediaId, null)),
                "telegram", "100", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Разбор корзины").contains(storedId.toString());

        // the profile was gathered first.
        RecordedRequest profileReq = mcpNutrition.takeRequest(2, TimeUnit.SECONDS);
        assertThat(profileReq.getPath()).startsWith("/internal/diet-profile");

        // caption got the media id + the SKILL + the profile + the user note.
        RecordedRequest captionReq = mediaProcessing.takeRequest(2, TimeUnit.SECONDS);
        assertThat(captionReq.getPath()).isEqualTo("/internal/caption");
        JsonNode captionBody = json.readTree(captionReq.getBody().readUtf8());
        assertThat(captionBody.path("mediaId").asText()).isEqualTo(mediaId);
        assertThat(captionBody.path("instruction").asText())
                .contains("strict JSON")     // the basket-analyst SKILL
                .contains("no-nuts")         // the folded diet profile
                .contains("разбери корзину"); // the user note

        // the basket was saved with the parsed items, source=receipt, the media id.
        RecordedRequest basketReq = mcpNutrition.takeRequest(2, TimeUnit.SECONDS);
        assertThat(basketReq.getPath()).isEqualTo("/internal/basket");
        JsonNode basketBody = json.readTree(basketReq.getBody().readUtf8());
        assertThat(basketBody.path("source").asText()).isEqualTo("receipt");
        assertThat(basketBody.path("ownerId").asText()).isEqualTo(userId.toString());
        assertThat(basketBody.path("receiptMediaId").asText()).isEqualTo(mediaId);
        assertThat(basketBody.path("merchant").asText()).isEqualTo("Лента");
        assertThat(basketBody.path("items")).hasSize(2);

        // the verdict board was rendered + uploaded.
        RecordedRequest mediaReq = mediaService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(mediaReq.getPath()).isEqualTo("/v1/media");
        String boardHtml = mediaReq.getBody().readUtf8();
        assertThat(boardHtml).contains("Разбор корзины").contains("молоко").contains("чипсы");
        // FD-c: the board is grounded in precise per-100g facts from mcp-food-data.
        assertThat(boardHtml).contains("Open Food Facts").contains("Nutri-Score B");

        // the items were enriched against mcp-food-data (one lookup per item).
        RecordedRequest foodReq = mcpFoodData.takeRequest(2, TimeUnit.SECONDS);
        assertThat(foodReq.getPath()).isEqualTo("/internal/food-lookup");
    }

    @Test
    void typedListWithoutProfileIsBrokenDownAndSaved() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID storedId = UUID.randomUUID();

        // GET /internal/diet-profile → none set (404).
        mcpNutrition.enqueue(new MockResponse().setResponseCode(404));
        // llm extract+breakdown.
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", DRAFT, "stop", new LlmUsage(80, 50, 130)))));
        // POST /internal/basket echo.
        mcpNutrition.enqueue(jsonResponse(json.writeValueAsString(basketEcho(householdId, userId, null))));
        mediaService.enqueue(jsonResponse(json.writeValueAsString(new MediaObjectDto(
                storedId, householdId, userId, "file", "text/html", 4096, "sha", "nutritionist", Instant.now()))));

        var msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "вот список покупок: молоко, чипсы", List.of(), "telegram", "101", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Разбор корзины").contains(storedId.toString());

        mcpNutrition.takeRequest(2, TimeUnit.SECONDS); // profile 404

        // the extract went through llm-gateway with the SKILL system prompt + the list.
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        assertThat(llmReq.getBody().readUtf8()).contains("strict JSON").contains("молоко, чипсы");

        // the basket saved with source=manual (no media id).
        RecordedRequest basketReq = mcpNutrition.takeRequest(2, TimeUnit.SECONDS);
        assertThat(basketReq.getPath()).isEqualTo("/internal/basket");
        JsonNode basketBody = json.readTree(basketReq.getBody().readUtf8());
        assertThat(basketBody.path("source").asText()).isEqualTo("manual");
    }

    @Test
    void notABasketRepliesWithoutSaving() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String mediaId = UUID.randomUUID().toString();

        mcpNutrition.enqueue(new MockResponse().setResponseCode(404)); // no profile
        mediaProcessing.enqueue(jsonResponse(json.writeValueAsString(
                new CaptionResult("{\"error\": \"not a basket\"}", "mock-vision"))));

        var msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE, "разбери корзину",
                List.of(new Attachment("image", "image/jpeg", mediaId, null)),
                "telegram", "102", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Не понял");

        mcpNutrition.takeRequest(2, TimeUnit.SECONDS);   // profile 404
        mediaProcessing.takeRequest(2, TimeUnit.SECONDS); // caption attempted
        // no basket save, no board upload.
        assertThat(mcpNutrition.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(mediaService.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    private BasketDto basketEcho(UUID householdId, UUID userId, String mediaId) {
        return new BasketDto(UUID.randomUUID(), householdId, userId, Instant.now(), "Лента",
                mediaId != null ? "receipt" : "manual",
                mediaId != null ? UUID.fromString(mediaId) : null,
                List.of(), 560, null, null, null, null, Instant.now());
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
