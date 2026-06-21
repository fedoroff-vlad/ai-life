package dev.fedorov.ailife.agents.stylist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.contracts.wardrobe.WardrobeItemDto;
import okhttp3.mockwebserver.Dispatcher;
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
 * Exercises the capsule advisor (ST-e): a text request → gather wardrobe + profile + trends on the
 * Coordinator → one LLM synthesis → render capsule HTML (with a garment gallery) → store in
 * media-service → reply with a link. MockWebServers stand in for mcp-wardrobe (items + profile),
 * mcp-web (trends), llm-gateway (synthesis) and media-service (HTML store).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StylistAdvisorTest {

    static MockWebServer mcpWardrobe;
    static MockWebServer mcpWeb;
    static MockWebServer llm;
    static MockWebServer mediaService;

    static final ObjectMapper MAPPER = new ObjectMapper();
    static volatile String itemsJson = "[]";

    @BeforeAll
    static void start() throws Exception {
        mcpWardrobe = new MockWebServer();
        mcpWeb = new MockWebServer();
        llm = new MockWebServer();
        mediaService = new MockWebServer();
        mcpWardrobe.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest req) {
                String path = req.getPath() == null ? "" : req.getPath();
                if (path.startsWith("/internal/items")) {
                    return json(itemsJson);
                }
                if (path.startsWith("/internal/profile")) {
                    return new MockResponse().setResponseCode(404); // no profile set — gather omits it
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        mcpWardrobe.start();
        mcpWeb.start();
        llm.start();
        mediaService.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpWardrobe.shutdown();
        mcpWeb.shutdown();
        llm.shutdown();
        mediaService.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("stylist-agent.mcp-wardrobe-url", () -> "http://localhost:" + mcpWardrobe.getPort());
        r.add("stylist-agent.mcp-web-url", () -> "http://localhost:" + mcpWeb.getPort());
        r.add("stylist-agent.media-service-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("stylist-agent.public-media-base-url", () -> "https://files.example.test");
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llm.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void capsuleRequestGathersSynthesizesAndReturnsHtmlLink() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        itemsJson = json.writeValueAsString(List.of(
                new WardrobeItemDto(UUID.randomUUID(), householdId, null, "navy coat", "outerwear",
                        "navy", "wool", "plain", "winter", "smart", imageId, Instant.now()),
                new WardrobeItemDto(UUID.randomUUID(), householdId, null, "white shirt", "top",
                        "white", "cotton", "plain", "all-season", "smart", null, Instant.now())));
        mcpWeb.enqueue(json("{\"query\":\"x\",\"hits\":[]}"));
        llm.enqueue(json(json.writeValueAsString(new LlmChatResponse(
                "mock-llm", "Капсула на зиму.\nLook 1: navy coat + white shirt.", "stop", null))));
        mediaService.enqueue(json(json.writeValueAsString(new MediaObjectDto(
                docId, householdId, userId, "file", "text/html", 999L, null, "stylist", Instant.now()))));

        var msg = new NormalizedMessage(
                userId, householdId, MessageScope.PRIVATE, "собери капсулу на зиму",
                List.of(), "telegram", "90", Instant.now());

        IntentResponse resp = http.post().uri("/agents/stylist/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Капсула на зиму");
        assertThat(resp.text()).contains("https://files.example.test/v1/media/" + docId);

        // mcp-web trends search happened.
        RecordedRequest searchReq = mcpWeb.takeRequest(2, TimeUnit.SECONDS);
        assertThat(searchReq.getPath()).isEqualTo("/internal/search");

        // The synthesis carried the gathered wardrobe in its context.
        RecordedRequest chatReq = llm.takeRequest(2, TimeUnit.SECONDS);
        assertThat(chatReq.getPath()).isEqualTo("/v1/chat");
        JsonNode chatBody = json.readTree(chatReq.getBody().readUtf8());
        String chatText = chatBody.toString();
        assertThat(chatText).contains("navy coat").contains("season");

        // The capsule HTML was stored, embedding the garment photo (the item with an image id).
        RecordedRequest uploadReq = mediaService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(uploadReq.getPath()).isEqualTo("/v1/media");
        String uploadBody = uploadReq.getBody().readUtf8();
        assertThat(uploadBody).contains("text/html").contains("<!DOCTYPE html>")
                .contains("/v1/media/" + imageId);
    }

    @Test
    void emptyWardrobeInvitesToCatalogueWithoutSynthesis() {
        itemsJson = "[]";
        UUID householdId = UUID.randomUUID();

        var msg = new NormalizedMessage(
                UUID.randomUUID(), householdId, MessageScope.PRIVATE, "что мне надеть сегодня",
                List.of(), "telegram", "91", Instant.now());

        IntentResponse resp = http.post().uri("/agents/stylist/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("гардероб");
        // No synthesis, no store on an empty wardrobe.
        assertThat(resp.text()).doesNotContain("/v1/media/");
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
