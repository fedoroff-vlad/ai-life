package dev.fedorov.ailife.agents.chef;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.contracts.web.WebSearchHit;
import dev.fedorov.ailife.contracts.web.WebSearchResult;
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
 * Exercises the recipe flow (CH-b) through the agent's HTTP surface ({@code POST /agents/chef/intent}).
 * A recipe-cue message searches mcp-web ({@code /internal/search}), synthesises one recipe card via
 * llm-gateway (the {@code recipe-finder} SKILL), renders it to HTML (card text + the real recipe links
 * from the hits) via the shared doc-render, stores it in media-service, and replies with a link. An
 * empty search still synthesises (the skill falls back to simple dishes, no links). MockWebServers
 * stand in for the capabilities.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RecipeFinderTest {

    static MockWebServer mcpWeb;
    static MockWebServer llmGateway;
    static MockWebServer mediaService;

    @BeforeAll
    static void start() throws Exception {
        mcpWeb = new MockWebServer();
        llmGateway = new MockWebServer();
        mediaService = new MockWebServer();
        mcpWeb.start();
        llmGateway.start();
        mediaService.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpWeb.shutdown();
        llmGateway.shutdown();
        mediaService.shutdown();
    }

    @AfterEach
    void drain() throws Exception {
        for (MockWebServer s : List.of(mcpWeb, llmGateway, mediaService)) {
            while (s.takeRequest(50, TimeUnit.MILLISECONDS) != null) {
                // discard
            }
        }
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("chef-agent.mcp-web-url", () -> "http://localhost:" + mcpWeb.getPort());
        r.add("chef-agent.media-service-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("chef-agent.public-media-base-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void recipeRequestSearchesSynthesisesAndRendersCardWithLinks() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID storedId = UUID.randomUUID();

        mcpWeb.enqueue(jsonResponse(json.writeValueAsString(new WebSearchResult("курица рецепт", List.of(
                new WebSearchHit("Курица с рисом", "https://food.ru/recipes/chicken-rice", "за 30 минут"),
                new WebSearchHit("Куриный суп", "https://food.ru/recipes/chicken-soup", "наваристый"))))));
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large",
                "Подобрал два рецепта с курицей.\nКурица с рисом — быстро и сытно.\nКуриный суп — на ужин.",
                "stop", new LlmUsage(150, 90, 240)))));
        mediaService.enqueue(jsonResponse(json.writeValueAsString(new MediaObjectDto(
                storedId, householdId, userId, "file", "text/html", 4096, "sha", "chef", Instant.now()))));

        var msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "что приготовить из курицы?", List.of(), "telegram", "200", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Рецепты").contains(storedId.toString());

        // the search was biased toward recipes.
        RecordedRequest searchReq = mcpWeb.takeRequest(2, TimeUnit.SECONDS);
        assertThat(searchReq.getPath()).isEqualTo("/internal/search");
        assertThat(searchReq.getBody().readUtf8()).contains("рецепт");

        // the synthesis carried the SKILL + the search hits in the context.
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        String llmBody = llmReq.getBody().readUtf8();
        assertThat(llmBody)
                .contains("recipe card")                       // the recipe-finder SKILL body
                .contains("Курица с рисом")                    // gathered hit folded into context
                .contains("https://food.ru/recipes/chicken-rice");

        // the rendered card carries the synthesized text + the real recipe links.
        RecordedRequest mediaReq = mediaService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(mediaReq.getPath()).isEqualTo("/v1/media");
        String cardHtml = mediaReq.getBody().readUtf8();
        assertThat(cardHtml)
                .contains("Рецепты")                            // board title
                .contains("Подобрал два рецепта")               // synthesized card text
                .contains("href=\"https://food.ru/recipes/chicken-rice\"")  // real clickable link
                .contains("Курица с рисом");
    }

    @Test
    void emptySearchStillSynthesisesWithoutLinks() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID storedId = UUID.randomUUID();

        mcpWeb.enqueue(jsonResponse(json.writeValueAsString(new WebSearchResult("рагу рецепт", List.of()))));
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "Не нашёл рецептов в сети, но вот простое рагу.", "stop",
                new LlmUsage(80, 50, 130)))));
        mediaService.enqueue(jsonResponse(json.writeValueAsString(new MediaObjectDto(
                storedId, householdId, userId, "file", "text/html", 2048, "sha", "chef", Instant.now()))));

        var msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "дай рецепт овощного рагу", List.of(), "telegram", "201", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Рецепты").contains(storedId.toString());

        mcpWeb.takeRequest(2, TimeUnit.SECONDS);   // search happened
        llmGateway.takeRequest(2, TimeUnit.SECONDS); // synthesis happened
        RecordedRequest mediaReq = mediaService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(mediaReq.getPath()).isEqualTo("/v1/media");
        // no recipe links in the card (empty search) — no link list markup.
        assertThat(mediaReq.getBody().readUtf8())
                .contains("Рецепты")
                .doesNotContain("class=\"links\"");
    }

    @Test
    void hubActionRecommendRecipesReturnsCardLink() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID storedId = UUID.randomUUID();

        mcpWeb.enqueue(jsonResponse(json.writeValueAsString(new WebSearchResult("рацион рецепт", List.of(
                new WebSearchHit("Овсянка", "https://food.ru/recipes/oats", "на завтрак"))))));
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "Рецепты под рацион.\nОвсянка на завтрак.", "stop",
                new LlmUsage(90, 60, 150)))));
        mediaService.enqueue(jsonResponse(json.writeValueAsString(new MediaObjectDto(
                storedId, householdId, userId, "file", "text/html", 2048, "sha", "chef", Instant.now()))));

        // the nutritionist (NU-g) invokes the chef over the hub with a ration in args.request.
        var req = new AgentActionRequest("chef", "recommend_recipes", householdId, userId, "nutritionist",
                json.createObjectNode().put("request", "рацион на неделю: завтрак — овсянка"));

        AgentActionResult result = http.post().uri("/agents/chef/actions/recommend_recipes")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(req)
                .exchange().expectStatus().isOk()
                .expectBody(AgentActionResult.class).returnResult().getResponseBody();

        assertThat(result).isNotNull();
        assertThat(result.ok()).isTrue();
        assertThat(result.result().path("link").asText()).contains(storedId.toString());

        assertThat(mcpWeb.takeRequest(2, TimeUnit.SECONDS).getPath()).isEqualTo("/internal/search");
        assertThat(llmGateway.takeRequest(2, TimeUnit.SECONDS).getPath()).isEqualTo("/v1/chat");
        assertThat(mediaService.takeRequest(2, TimeUnit.SECONDS).getPath()).isEqualTo("/v1/media");
    }

    @Test
    void hubActionRejectsUnknownAction() {
        var req = new AgentActionRequest("chef", "do_something", UUID.randomUUID(), UUID.randomUUID(),
                "nutritionist", json.createObjectNode().put("request", "x"));

        AgentActionResult result = http.post().uri("/agents/chef/actions/do_something")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(req)
                .exchange().expectStatus().isOk()
                .expectBody(AgentActionResult.class).returnResult().getResponseBody();

        assertThat(result).isNotNull();
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("unknown action");
    }

    private IntentResponse post(NormalizedMessage msg) {
        return http.post().uri("/agents/chef/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
