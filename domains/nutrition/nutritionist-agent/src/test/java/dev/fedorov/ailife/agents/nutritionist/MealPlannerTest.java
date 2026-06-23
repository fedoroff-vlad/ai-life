package dev.fedorov.ailife.agents.nutritionist;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.contracts.nutrition.DietProfileDto;
import dev.fedorov.ailife.contracts.nutrition.MealLogDto;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the ration + shopping-list flow (NU-g) through the agent's HTTP surface ({@code POST
 * /agents/nutritionist/intent}). A ration-cue message gathers — in parallel on the shared
 * {@code Coordinator} — the sender's diet profile + the household-default profile + recent meals
 * (mcp-nutrition) and, when a store is named, that store's availability (mcp-web), synthesises one
 * ration via llm-gateway (the {@code meal-planner} SKILL), renders it to HTML via the shared
 * doc-render, stores it in media-service, and replies with a link. A request with no named store
 * skips the web search. The gather is parallel, so the mcp-nutrition / mcp-web stubs route by path
 * (a Dispatcher) rather than a FIFO queue. MockWebServers stand in for the capabilities.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MealPlannerTest {

    static MockWebServer mcpNutrition;
    static MockWebServer mcpWeb;
    static MockWebServer llmGateway;
    static MockWebServer mediaService;
    static MockWebServer orchestrator;

    @BeforeAll
    static void start() throws Exception {
        mcpNutrition = new MockWebServer();
        mcpWeb = new MockWebServer();
        llmGateway = new MockWebServer();
        mediaService = new MockWebServer();
        orchestrator = new MockWebServer();
        mcpNutrition.start();
        mcpWeb.start();
        llmGateway.start();
        mediaService.start();
        orchestrator.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpNutrition.shutdown();
        mcpWeb.shutdown();
        llmGateway.shutdown();
        mediaService.shutdown();
        orchestrator.shutdown();
    }

    /** Drain any recorded requests so the static servers stay isolated across (unordered) tests. */
    @AfterEach
    void drain() throws Exception {
        for (MockWebServer s : List.of(mcpNutrition, mcpWeb, llmGateway, mediaService, orchestrator)) {
            while (s.takeRequest(50, TimeUnit.MILLISECONDS) != null) {
                // discard
            }
        }
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("nutritionist-agent.mcp-nutrition-url", () -> "http://localhost:" + mcpNutrition.getPort());
        r.add("nutritionist-agent.mcp-web-url", () -> "http://localhost:" + mcpWeb.getPort());
        r.add("nutritionist-agent.media-service-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("nutritionist-agent.public-media-base-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("nutritionist-agent.orchestrator-url", () -> "http://localhost:" + orchestrator.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void rationWithNamedStoreGathersProfilesMealsStoreThenRendersAndLinks() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID storedId = UUID.randomUUID();

        mcpNutrition.setDispatcher(nutritionDispatcher(householdId, userId, true));
        mcpWeb.setDispatcher(searchDispatcher());
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large",
                "Сбалансированный план на неделю для семьи.\nРацион: завтрак — каша...\n"
                        + "Список покупок: овсянка, курица, овощи.",
                "stop", new LlmUsage(200, 120, 320)))));
        mediaService.enqueue(jsonResponse(json.writeValueAsString(new MediaObjectDto(
                storedId, householdId, userId, "file", "text/html", 4096, "sha", "nutritionist",
                Instant.now()))));
        // chef (via the hub) returns a recipe card link.
        orchestrator.enqueue(jsonResponse(json.writeValueAsString(AgentActionResult.ok(
                json.createObjectNode().put("link", "http://chef.example/v1/media/recipes").put("summary", "3 рецепта")))));

        var msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "Хотим закупиться в Ленте на неделю: я, жена, и малыш 8 месяцев на прикорме",
                List.of(), "telegram", "120", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Рацион и список покупок").contains(storedId.toString());
        // the chef's recipe card link was folded into the reply.
        assertThat(resp.text()).contains("Рецепты от шефа").contains("http://chef.example/v1/media/recipes");

        // the chef was invoked over the orchestrator hub for recipes.
        RecordedRequest invokeReq = orchestrator.takeRequest(2, TimeUnit.SECONDS);
        assertThat(invokeReq.getPath()).isEqualTo("/v1/agents/invoke");
        String invokeBody = invokeReq.getBody().readUtf8();
        assertThat(invokeBody)
                .contains("\"targetAgent\":\"chef\"")
                .contains("\"action\":\"recommend_recipes\"")
                .contains("\"requestingAgent\":\"nutritionist\"");

        // mcp-nutrition was hit for the self profile, the household profile, and the recent meals.
        List<String> nutritionPaths = drainPaths(mcpNutrition, 3);
        assertThat(nutritionPaths).anyMatch(p -> p.startsWith("/internal/diet-profile") && p.contains("ownerId=" + userId));
        assertThat(nutritionPaths).anyMatch(p -> p.startsWith("/internal/diet-profile") && !p.contains("ownerId="));
        assertThat(nutritionPaths).anyMatch(p -> p.startsWith("/internal/meals"));

        // the named store triggered a web availability search.
        RecordedRequest searchReq = mcpWeb.takeRequest(2, TimeUnit.SECONDS);
        assertThat(searchReq.getPath()).isEqualTo("/internal/search");
        assertThat(searchReq.getBody().readUtf8()).contains("лент");

        // the synthesis carried the SKILL + the gathered profiles/meals/store in the context.
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        String llmBody = llmReq.getBody().readUtf8();
        assertThat(llmBody)
                .contains("ration")           // the meal-planner SKILL body
                .contains("shopping list")    // the SKILL body
                .contains("2000")             // gathered self-profile goal
                .contains("овсянка")          // gathered recent meal
                .contains("food.ru");         // gathered store search hit

        // the rendered HTML page was uploaded.
        RecordedRequest mediaReq = mediaService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(mediaReq.getPath()).isEqualTo("/v1/media");
        assertThat(mediaReq.getBody().readUtf8()).contains("Рацион и список покупок");
    }

    @Test
    void rationWithoutNamedStoreSkipsWebSearch() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID storedId = UUID.randomUUID();

        mcpNutrition.setDispatcher(nutritionDispatcher(householdId, userId, false));
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "План на неделю.\nСписок покупок: ...", "stop",
                new LlmUsage(150, 90, 240)))));
        mediaService.enqueue(jsonResponse(json.writeValueAsString(new MediaObjectDto(
                storedId, householdId, userId, "file", "text/html", 2048, "sha", "nutritionist",
                Instant.now()))));
        // chef declines (ok=false) → the recipes line is soft-failed out.
        orchestrator.enqueue(jsonResponse(json.writeValueAsString(
                AgentActionResult.error("no recipes"))));

        var msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "составь рацион на неделю", List.of(), "telegram", "121", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Рацион и список покупок").contains(storedId.toString());
        // chef declined → no recipes line, but the ration still ships.
        assertThat(resp.text()).doesNotContain("Рецепты от шефа");

        // no store named → no web search call.
        assertThat(mcpWeb.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();

        // synthesis + store still happened, and the chef was still invoked.
        assertThat(llmGateway.takeRequest(2, TimeUnit.SECONDS).getPath()).isEqualTo("/v1/chat");
        assertThat(mediaService.takeRequest(2, TimeUnit.SECONDS).getPath()).isEqualTo("/v1/media");
        assertThat(orchestrator.takeRequest(2, TimeUnit.SECONDS).getPath()).isEqualTo("/v1/agents/invoke");
    }

    /** Routes the parallel mcp-nutrition gather by path: self profile / household profile / meals. */
    private Dispatcher nutritionDispatcher(UUID householdId, UUID userId, boolean withMeals) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                try {
                    if (path.startsWith("/internal/diet-profile")) {
                        boolean self = path.contains("ownerId=");
                        return jsonResponse(json.writeValueAsString(new DietProfileDto(
                                UUID.randomUUID(), householdId, self ? userId : null,
                                self ? 2000 : 1800, new BigDecimal("140"), null, null,
                                json.readTree(self ? "[\"no-nuts\"]" : "[\"halal\"]"), null,
                                self ? "cutting" : "family", Instant.now())));
                    }
                    if (path.startsWith("/internal/meals")) {
                        return jsonResponse(json.writeValueAsString(List.of(
                                new MealLogDto(UUID.randomUUID(), householdId, userId, Instant.now(),
                                        "text", "овсянка с бананом", null, 420, new BigDecimal("12"),
                                        new BigDecimal("8"), new BigDecimal("70"), null, Instant.now()))));
                    }
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500);
                }
                return new MockResponse().setResponseCode(404);
            }
        };
    }

    /** mcp-web availability search → one food.ru-style hit. */
    private Dispatcher searchDispatcher() {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                try {
                    return jsonResponse(json.writeValueAsString(new WebSearchResult(
                            "ассортимент",
                            List.of(new WebSearchHit("Ассортимент Лента", "https://food.ru/lenta",
                                    "сезонные продукты")))));
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500);
                }
            }
        };
    }

    private List<String> drainPaths(MockWebServer server, int count) throws InterruptedException {
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
            if (req == null) break;
            paths.add(req.getPath());
        }
        return paths;
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
