package dev.fedorov.ailife.agents.nutritionist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.basket.BasketCapturedEvent;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.contracts.nutrition.BasketDto;
import dev.fedorov.ailife.contracts.nutrition.BasketItem;
import dev.fedorov.ailife.contracts.profile.UserDto;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the IA-b grocery fan-out consume path through {@code POST /internal/basket-event} —
 * the endpoint mcp-nutrition's bus consumer (IA-b2) forwards a {@code basket.captured} event to.
 * The agent runs one LLM breakdown over the line items finance already extracted (the
 * {@code basket-analyst} SKILL), saves the basket, renders the verdict board to media-service, and
 * notifies the household. MockWebServers stand in for the capabilities.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BasketEventFlowTest {

    static MockWebServer mcpNutrition;
    static MockWebServer llmGateway;
    static MockWebServer mediaService;
    static MockWebServer profileService;
    static MockWebServer notifier;
    static MockWebServer mcpFoodData;

    @BeforeAll
    static void start() throws Exception {
        mcpNutrition = new MockWebServer();
        llmGateway = new MockWebServer();
        mediaService = new MockWebServer();
        profileService = new MockWebServer();
        notifier = new MockWebServer();
        mcpFoodData = new MockWebServer();
        mcpNutrition.start();
        llmGateway.start();
        mediaService.start();
        profileService.start();
        notifier.start();
        mcpFoodData.start();
        // FD-c: the bus-fan-out breakdown also enriches via mcp-food-data (shared render path).
        mcpFoodData.setDispatcher(FOOD_LOOKUP_DISPATCHER);
    }

    @AfterAll
    static void stop() throws Exception {
        mcpNutrition.shutdown();
        llmGateway.shutdown();
        mediaService.shutdown();
        profileService.shutdown();
        notifier.shutdown();
        mcpFoodData.shutdown();
    }

    @AfterEach
    void drain() throws Exception {
        for (MockWebServer s : List.of(mcpNutrition, llmGateway, mediaService, profileService, notifier, mcpFoodData)) {
            while (s.takeRequest(50, TimeUnit.MILLISECONDS) != null) {
                // discard
            }
        }
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("nutritionist-agent.mcp-nutrition-url", () -> "http://localhost:" + mcpNutrition.getPort());
        r.add("nutritionist-agent.media-service-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("nutritionist-agent.public-media-base-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("nutritionist-agent.profile-service-url", () -> "http://localhost:" + profileService.getPort());
        r.add("nutritionist-agent.notifier-url", () -> "http://localhost:" + notifier.getPort());
        r.add("nutritionist-agent.mcp-food-data-url", () -> "http://localhost:" + mcpFoodData.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    /** Every food-lookup → a matched product (the FD-c enrichment runs on the bus path too). */
    private static final Dispatcher FOOD_LOOKUP_DISPATCHER = new Dispatcher() {
        @Override
        public MockResponse dispatch(RecordedRequest request) {
            return new MockResponse().setHeader("content-type", "application/json").setBody(
                    "{\"name\":\"Молоко 3.2%\",\"brand\":\"Простоквашино\",\"kcal100g\":60,"
                    + "\"protein100g\":3.0,\"fat100g\":3.2,\"carbs100g\":4.7}");
        }
    };

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    private static final String DRAFT =
            "{\"merchant\":\"Лента\","
            + "\"items\":[{\"name\":\"молоко\",\"qty\":\"1 л\"},{\"name\":\"чипсы\",\"qty\":\"1 шт\"}],"
            + "\"totals\":{\"kcal\":560},"
            + "\"analysis\":{\"good\":[{\"name\":\"молоко\",\"reason\":\"белок\"}],\"watch\":[],"
            + "\"cut\":[{\"name\":\"чипсы\",\"reason\":\"ультра-обработанное\"}]},"
            + "\"summary\":\"В целом ок, убрать чипсы.\"}";

    @Test
    void basketEventBreaksDownSavesRendersAndNotifiesHousehold() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID storedId = UUID.randomUUID();
        UUID receiptMediaId = UUID.randomUUID();

        // GET /internal/diet-profile → none set (404 → breakdown proceeds without goals).
        mcpNutrition.enqueue(new MockResponse().setResponseCode(404));
        // LLM breakdown.
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", DRAFT, "stop", new LlmUsage(90, 60, 150)))));
        // POST /internal/basket echo.
        mcpNutrition.enqueue(jsonResponse(json.writeValueAsString(new BasketDto(
                UUID.randomUUID(), householdId, null, Instant.now(), "Лента", "receipt", receiptMediaId,
                List.of(), 560, null, null, null, null, Instant.now()))));
        // media-service stores the board.
        mediaService.enqueue(jsonResponse(json.writeValueAsString(new MediaObjectDto(
                storedId, householdId, null, "file", "text/html", 4096, "sha", "nutritionist", Instant.now()))));
        // profile-service household members.
        profileService.enqueue(jsonResponse(json.writeValueAsString(List.of(new UserDto(
                userId, householdId, "Влад", "ru-RU", 42L, "member", Instant.now())))));
        // notifier delivery.
        notifier.enqueue(new MockResponse().setResponseCode(200));

        BasketCapturedEvent event = new BasketCapturedEvent(householdId, null, "Лента",
                List.of(new BasketItem("молоко", "1 л", null, null, null, null),
                        new BasketItem("чипсы", "1 шт", null, null, null, null)),
                receiptMediaId, Instant.now());

        http.post().uri("/internal/basket-event")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(event)
                .exchange().expectStatus().isAccepted();

        // The breakdown went through llm-gateway with the SKILL + the item list.
        mcpNutrition.takeRequest(2, TimeUnit.SECONDS); // diet-profile
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        assertThat(llmReq.getBody().readUtf8()).contains("strict JSON").contains("молоко");

        // The basket was saved (source=receipt, the receipt media id).
        RecordedRequest basketReq = mcpNutrition.takeRequest(2, TimeUnit.SECONDS);
        assertThat(basketReq.getPath()).isEqualTo("/internal/basket");
        JsonNode basketBody = json.readTree(basketReq.getBody().readUtf8());
        assertThat(basketBody.path("source").asText()).isEqualTo("receipt");
        assertThat(basketBody.path("receiptMediaId").asText()).isEqualTo(receiptMediaId.toString());

        // The verdict board was rendered + uploaded.
        RecordedRequest mediaReq = mediaService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(mediaReq.getPath()).isEqualTo("/v1/media");
        assertThat(mediaReq.getBody().readUtf8()).contains("Разбор корзины");

        // The household was notified with the summary + the deliverable link.
        RecordedRequest usersReq = profileService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(usersReq.getPath()).startsWith("/v1/users/by-household/");
        RecordedRequest notifyReq = notifier.takeRequest(2, TimeUnit.SECONDS);
        assertThat(notifyReq.getPath()).isEqualTo("/v1/notify");
        JsonNode notifyBody = json.readTree(notifyReq.getBody().readUtf8());
        assertThat(notifyBody.path("userId").asText()).isEqualTo(userId.toString());
        assertThat(notifyBody.path("text").asText())
                .contains("Разбор корзины").contains(storedId.toString());
    }

    @Test
    void emptyItemsEventIsIgnored() throws Exception {
        UUID householdId = UUID.randomUUID();

        BasketCapturedEvent event = new BasketCapturedEvent(householdId, null, "Лента",
                List.of(), null, Instant.now());

        http.post().uri("/internal/basket-event")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(event)
                .exchange().expectStatus().isAccepted();

        // Nothing to break down → no LLM, no save, no notify.
        assertThat(llmGateway.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(mcpNutrition.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(notifier.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
