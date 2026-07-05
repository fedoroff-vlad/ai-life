package dev.fedorov.ailife.agents.creator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.creator.ContentPieceDto;
import dev.fedorov.ailife.contracts.creator.CreatorProfileDto;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
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
 * Exercises the content-strategist flow (CR-d) through the agent's HTTP surface ({@code POST
 * /agents/creator/intent}): a trend/ideas request → resolve the creator track → gather web + YouTube +
 * Reddit in parallel → one llm-gateway synthesis with the {@code content-strategist} SKILL → render the
 * HTML content-plan board → store in media-service → reply with the link. MockWebServers stand in for
 * mcp-creator, the three sources, llm-gateway, and media-service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ContentStrategistTest {

    static MockWebServer mcpCreator;
    static MockWebServer mcpWeb;
    static MockWebServer mcpYoutube;
    static MockWebServer mcpReddit;
    static MockWebServer llmGateway;
    static MockWebServer media;

    @BeforeAll
    static void start() throws Exception {
        mcpCreator = new MockWebServer(); mcpCreator.start();
        mcpWeb = new MockWebServer(); mcpWeb.start();
        mcpYoutube = new MockWebServer(); mcpYoutube.start();
        mcpReddit = new MockWebServer(); mcpReddit.start();
        llmGateway = new MockWebServer(); llmGateway.start();
        media = new MockWebServer(); media.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpCreator.shutdown(); mcpWeb.shutdown(); mcpYoutube.shutdown();
        mcpReddit.shutdown(); llmGateway.shutdown(); media.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("creator-agent.mcp-creator-url", () -> "http://localhost:" + mcpCreator.getPort());
        r.add("creator-agent.mcp-web-url", () -> "http://localhost:" + mcpWeb.getPort());
        r.add("creator-agent.mcp-youtube-url", () -> "http://localhost:" + mcpYoutube.getPort());
        r.add("creator-agent.mcp-reddit-url", () -> "http://localhost:" + mcpReddit.getPort());
        r.add("creator-agent.media-service-url", () -> "http://localhost:" + media.getPort());
        r.add("creator-agent.public-media-base-url", () -> "http://localhost:" + media.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void trendRequestGathersSynthesizesRendersAndReturnsLink() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();

        // Creator track (self) → drives the niche.
        mcpCreator.enqueue(jsonResponse(json.writeValueAsString(new CreatorProfileDto(
                UUID.randomUUID(), householdId, userId, "English for IT", "junior devs",
                "friendly-expert", json.readTree("[\"youtube\",\"reddit\"]"), null, null, null,
                Instant.now()))));

        // Three sources each return one hit (web's WebSearchResult, youtube/reddit a TrendHit list).
        mcpWeb.enqueue(jsonResponse("""
                { "query": "English for IT", "hits": [
                  { "title": "IT English guide", "url": "https://blog.example.com/guide", "snippet": "tips" } ] }
                """));
        mcpYoutube.enqueue(jsonResponse("""
                [ { "source": "youtube", "platform": "youtube", "title": "Idioms for devs",
                    "url": "https://www.youtube.com/watch?v=abc", "summary": "fun" } ]
                """));
        mcpReddit.enqueue(jsonResponse("""
                [ { "source": "reddit", "platform": "reddit", "title": "How I learned IT English",
                    "url": "https://www.reddit.com/r/x/1/", "summary": "story" } ]
                """));

        // One synthesis turn.
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large",
                "Сейчас в нише горячо.\nТренд: idioms.\nИдея: short про git.\nДрафт: ...",
                "stop", new LlmUsage(120, 80, 200)))));

        // The gathered corpus is cached, then the plan is saved as a draft (CR-e).
        mcpCreator.enqueue(jsonResponse("[]"));
        mcpCreator.enqueue(jsonResponse(json.writeValueAsString(new ContentPieceDto(
                UUID.randomUUID(), householdId, userId, "draft", null, "Контент-план: English for IT",
                "body", null, null, "new", null, Instant.now()))));

        // media-service stores the HTML board.
        media.enqueue(jsonResponse(json.writeValueAsString(new MediaObjectDto(
                mediaId, householdId, userId, "file", "text/html", 2048, "sha", "creator", Instant.now()))));

        var msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "что сейчас в тренде, дай идеи для постов", List.of(), "telegram", "100", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text())
                .contains("Сейчас в нише горячо.")
                .contains("/v1/media/" + mediaId);

        // The synthesis carried the niche + the gathered corpus from all three sources.
        RecordedRequest llmReq = llmGateway.takeRequest(3, TimeUnit.SECONDS);
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        String llmBody = llmReq.getBody().readUtf8();
        assertThat(llmBody)
                .contains("English for IT")
                .contains("IT English guide")
                .contains("Idioms for devs")
                .contains("How I learned IT English");

        // The rendered board was uploaded as HTML.
        RecordedRequest upload = media.takeRequest(3, TimeUnit.SECONDS);
        assertThat(upload.getPath()).isEqualTo("/v1/media");
        assertThat(upload.getBody().readUtf8()).contains("text/html");

        // mcp-creator saw: the profile read, then the batched trend cache, then the draft piece.
        mcpCreator.takeRequest(3, TimeUnit.SECONDS);   // GET /internal/creator-profile
        RecordedRequest trendsReq = mcpCreator.takeRequest(3, TimeUnit.SECONDS);
        assertThat(trendsReq.getPath()).isEqualTo("/internal/trends");
        assertThat(trendsReq.getBody().readUtf8())
                .contains("https://blog.example.com/guide")     // web hit
                .contains("https://www.youtube.com/watch?v=abc") // youtube hit
                .contains("https://www.reddit.com/r/x/1/");      // reddit hit
        RecordedRequest pieceReq = mcpCreator.takeRequest(3, TimeUnit.SECONDS);
        assertThat(pieceReq.getPath()).isEqualTo("/internal/content-piece");
        assertThat(pieceReq.getBody().readUtf8())
                .contains("\"draft\"")
                .contains("English for IT");
    }

    @Test
    void oneSourceFailingStillProducesAPlan() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();

        // No profile (self 404, then household 404) → the niche falls back to the request text.
        mcpCreator.enqueue(new MockResponse().setResponseCode(404));
        mcpCreator.enqueue(new MockResponse().setResponseCode(404));

        // web ok, youtube ok, reddit fails — the gather soft-fails reddit and synthesis still runs.
        mcpWeb.enqueue(jsonResponse("""
                { "query": "q", "hits": [
                  { "title": "Web hit", "url": "https://e.com/a", "snippet": "s" } ] }
                """));
        mcpYoutube.enqueue(jsonResponse("""
                [ { "source": "youtube", "platform": "youtube", "title": "YT hit",
                    "url": "https://www.youtube.com/watch?v=z" } ]
                """));
        mcpReddit.enqueue(new MockResponse().setResponseCode(500));

        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "План готов.\nИдея 1.\nИдея 2.", "stop", new LlmUsage(40, 30, 70)))));
        // Persist the (two-source) gather + the draft piece (CR-e).
        mcpCreator.enqueue(jsonResponse("[]"));
        mcpCreator.enqueue(jsonResponse(json.writeValueAsString(new ContentPieceDto(
                UUID.randomUUID(), householdId, userId, "draft", null, "Контент-план: подкасты",
                "body", null, null, "new", null, Instant.now()))));
        media.enqueue(jsonResponse(json.writeValueAsString(new MediaObjectDto(
                mediaId, householdId, userId, "file", "text/html", 1024, "sha", "creator", Instant.now()))));

        var msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "дай идеи для постов про подкасты", List.of(), "telegram", "102", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("План готов.").contains("/v1/media/" + mediaId);

        // The synthesis ran with the two healthy sources; the failed one was simply omitted.
        RecordedRequest llmReq = llmGateway.takeRequest(3, TimeUnit.SECONDS);
        String body = llmReq.getBody().readUtf8();
        assertThat(body).contains("Web hit").contains("YT hit");
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
