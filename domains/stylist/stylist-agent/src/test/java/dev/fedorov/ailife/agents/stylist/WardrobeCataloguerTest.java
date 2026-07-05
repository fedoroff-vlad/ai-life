package dev.fedorov.ailife.agents.stylist;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.Attachment;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.media.CaptionResult;
import dev.fedorov.ailife.contracts.wardrobe.WardrobeItemDto;
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
 * Exercises the wardrobe-catalogue flow (ST-c) through the agent's HTTP surface: a photo intent
 * ({@code POST /agents/stylist/intent}) asks the shared mcp-media-processing {@code caption}
 * passthrough for a structured garment extract, then writes the item via mcp-wardrobe's
 * {@code /internal/item}. Write-immediately — no confirm step. MockWebServers stand in for the two
 * capabilities (the MCP/SSE transport can't be MockWebServer'd, hence the HTTP passthroughs).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class WardrobeCataloguerTest {

    static MockWebServer mediaProcessing;
    static MockWebServer mcpWardrobe;

    @BeforeAll
    static void start() throws Exception {
        mediaProcessing = new MockWebServer();
        mcpWardrobe = new MockWebServer();
        mediaProcessing.start();
        mcpWardrobe.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mediaProcessing.shutdown();
        mcpWardrobe.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("stylist-agent.mcp-media-processing-url", () -> "http://localhost:" + mediaProcessing.getPort());
        r.add("stylist-agent.mcp-wardrobe-url", () -> "http://localhost:" + mcpWardrobe.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void photoAttachmentIsCaptionedThenWrittenToWardrobe() throws Exception {
        UUID householdId = UUID.randomUUID();
        String mediaId = UUID.randomUUID().toString();

        var draftJson = "{\"name\": \"navy wool coat\", \"category\": \"outerwear\", "
                + "\"colour\": \"navy\", \"material\": \"wool\", \"pattern\": \"plain\", "
                + "\"season\": \"winter\", \"formality\": \"smart\"}";
        mediaProcessing.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new CaptionResult(draftJson, "mock-vision"))));
        mcpWardrobe.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new WardrobeItemDto(
                        UUID.randomUUID(), householdId, null, "navy wool coat", "outerwear",
                        "navy", "wool", "plain", "winter", "smart",
                        UUID.fromString(mediaId), Instant.now()))));

        var msg = new NormalizedMessage(
                UUID.randomUUID(), householdId, MessageScope.PRIVATE, "это моё зимнее пальто",
                List.of(new Attachment("image", "image/jpeg", mediaId, null)),
                "telegram", "70", Instant.now());

        IntentResponse resp = http.post().uri("/agents/stylist/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("гардероб").contains("navy wool coat");
        assertThat(resp.pendingAction()).isNull(); // write-immediately, no confirm lock

        // The caption passthrough got the media id + the SKILL instruction + the user note.
        RecordedRequest captionReq = mediaProcessing.takeRequest(2, TimeUnit.SECONDS);
        assertThat(captionReq.getMethod()).isEqualTo("POST");
        assertThat(captionReq.getPath()).isEqualTo("/internal/caption");
        JsonNode captionBody = json.readTree(captionReq.getBody().readUtf8());
        assertThat(captionBody.path("mediaId").asText()).isEqualTo(mediaId);
        assertThat(captionBody.path("instruction").asText())
                .contains("strict JSON")             // the wardrobe-cataloguer SKILL.md prompt
                .contains("это моё зимнее пальто");   // the user's caption folded in as a hint

        // The garment was written with the parsed fields + the photo media id.
        RecordedRequest addReq = mcpWardrobe.takeRequest(2, TimeUnit.SECONDS);
        assertThat(addReq.getMethod()).isEqualTo("POST");
        assertThat(addReq.getPath()).isEqualTo("/internal/item");
        JsonNode addBody = json.readTree(addReq.getBody().readUtf8());
        assertThat(addBody.path("householdId").asText()).isEqualTo(householdId.toString());
        assertThat(addBody.path("name").asText()).isEqualTo("navy wool coat");
        assertThat(addBody.path("category").asText()).isEqualTo("outerwear");
        assertThat(addBody.path("imageMediaId").asText()).isEqualTo(mediaId);
    }

    @Test
    void notAGarmentRepliesWithoutWriting() throws Exception {
        UUID householdId = UUID.randomUUID();
        String mediaId = UUID.randomUUID().toString();

        mediaProcessing.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(
                        new CaptionResult("{\"error\": \"not a garment\"}", "mock-vision"))));

        var msg = new NormalizedMessage(
                UUID.randomUUID(), householdId, MessageScope.PRIVATE, null,
                List.of(new Attachment("image", "image/jpeg", mediaId, null)),
                "telegram", "71", Instant.now());

        IntentResponse resp = http.post().uri("/agents/stylist/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Не понял");

        // Caption was attempted; no wardrobe write happened.
        mediaProcessing.takeRequest(2, TimeUnit.SECONDS);
        assertThat(mcpWardrobe.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }
}
