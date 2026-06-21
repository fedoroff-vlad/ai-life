package dev.fedorov.ailife.agents.stylist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.Attachment;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.media.CaptionResult;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.contracts.wardrobe.StyleProfileDto;
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
 * Exercises the "analyse me" flow (ST-d): a self-photo + typed body params → caption analysis →
 * set_style_profile → render analysis HTML → store in media-service → reply with a link. The photo
 * is routed to analyse (not catalogue) by the caption keyword heuristic. MockWebServers stand in
 * for mcp-media-processing (caption), mcp-wardrobe (profile) and media-service (HTML store).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnalyseMeTest {

    static MockWebServer mediaProcessing;
    static MockWebServer mcpWardrobe;
    static MockWebServer mediaService;

    @BeforeAll
    static void start() throws Exception {
        mediaProcessing = new MockWebServer();
        mcpWardrobe = new MockWebServer();
        mediaService = new MockWebServer();
        mediaProcessing.start();
        mcpWardrobe.start();
        mediaService.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mediaProcessing.shutdown();
        mcpWardrobe.shutdown();
        mediaService.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("stylist-agent.mcp-media-processing-url", () -> "http://localhost:" + mediaProcessing.getPort());
        r.add("stylist-agent.mcp-wardrobe-url", () -> "http://localhost:" + mcpWardrobe.getPort());
        r.add("stylist-agent.media-service-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("stylist-agent.public-media-base-url", () -> "https://files.example.test");
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void selfPhotoWithParamsBuildsProfileAndReturnsHtmlLink() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String mediaId = UUID.randomUUID().toString();
        UUID docId = UUID.randomUUID();

        var profileJson = "{\"personType\": \"classic\", \"bodyShape\": \"hourglass\", "
                + "\"colourType\": \"winter\", \"suitableFabrics\": [\"wool\", \"silk\"], "
                + "\"heightCm\": 180, \"weightKg\": 72, \"measurements\": {\"chest\": 100, \"waist\": 80}}";
        mediaProcessing.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new CaptionResult(profileJson, "mock-vision"))));
        // mcp-wardrobe /internal/profile echoes the saved profile.
        mcpWardrobe.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new StyleProfileDto(
                        UUID.randomUUID(), householdId, userId, "classic", "hourglass", "winter",
                        json.readTree("[\"wool\",\"silk\"]"), 180, new java.math.BigDecimal("72"),
                        json.readTree("{\"chest\":100,\"waist\":80}"), null,
                        UUID.fromString(mediaId), Instant.now()))));
        // media-service stores the HTML deliverable.
        mediaService.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new MediaObjectDto(
                        docId, householdId, userId, "file", "text/html", 1234L, null, "stylist", Instant.now()))));

        var msg = new NormalizedMessage(
                userId, householdId, MessageScope.PRIVATE,
                "проанализируй меня, рост 180 вес 72",
                List.of(new Attachment("image", "image/jpeg", mediaId, null)),
                "telegram", "80", Instant.now());

        IntentResponse resp = http.post().uri("/agents/stylist/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("winter").contains("hourglass");
        // The deliverable link uses the configured public base + the stored media id.
        assertThat(resp.text()).contains("https://files.example.test/v1/media/" + docId);

        // Caption got the style-analyst instruction + the user's note (params).
        RecordedRequest captionReq = mediaProcessing.takeRequest(2, TimeUnit.SECONDS);
        assertThat(captionReq.getPath()).isEqualTo("/internal/caption");
        JsonNode captionBody = json.readTree(captionReq.getBody().readUtf8());
        assertThat(captionBody.path("instruction").asText())
                .contains("strict JSON")
                .contains("рост 180 вес 72");

        // Profile was upserted with owner = the user, carrying the parsed fields.
        RecordedRequest profileReq = mcpWardrobe.takeRequest(2, TimeUnit.SECONDS);
        assertThat(profileReq.getMethod()).isEqualTo("POST");
        assertThat(profileReq.getPath()).isEqualTo("/internal/profile");
        JsonNode profileBody = json.readTree(profileReq.getBody().readUtf8());
        assertThat(profileBody.path("ownerId").asText()).isEqualTo(userId.toString());
        assertThat(profileBody.path("colourType").asText()).isEqualTo("winter");
        assertThat(profileBody.path("heightCm").asInt()).isEqualTo(180);

        // The HTML deliverable was uploaded to media-service as text/html.
        RecordedRequest uploadReq = mediaService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(uploadReq.getMethod()).isEqualTo("POST");
        assertThat(uploadReq.getPath()).isEqualTo("/v1/media");
        String uploadBody = uploadReq.getBody().readUtf8();
        assertThat(uploadBody).contains("text/html").contains("<!DOCTYPE html>");
    }

    @Test
    void notAPersonPhotoRepliesWithoutWriting() throws Exception {
        UUID householdId = UUID.randomUUID();
        String mediaId = UUID.randomUUID().toString();

        mediaProcessing.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(
                        new CaptionResult("{\"error\": \"not a person photo\"}", "mock-vision"))));

        var msg = new NormalizedMessage(
                UUID.randomUUID(), householdId, MessageScope.PRIVATE, "проанализируй меня",
                List.of(new Attachment("image", "image/jpeg", mediaId, null)),
                "telegram", "81", Instant.now());

        IntentResponse resp = http.post().uri("/agents/stylist/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Не понял");

        mediaProcessing.takeRequest(2, TimeUnit.SECONDS);
        // No profile write, no HTML store.
        assertThat(mcpWardrobe.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(mediaService.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }
}
