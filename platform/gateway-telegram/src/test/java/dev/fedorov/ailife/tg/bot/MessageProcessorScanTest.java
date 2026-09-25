package dev.fedorov.ailife.tg.bot;

import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.contracts.media.QrResult;
import dev.fedorov.ailife.contracts.profile.UserDto;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The front-door half of the scan path (IN-f1): a decoded {@code box_<token>} is dispatched to inventory
 * <b>deterministically through the hub</b> ({@code POST /v1/agents/invoke}), not classified as a message.
 *
 * <p>That is the point of the slice: a sticker carries no sentence, so sending it through the LLM
 * classifier would put a guess between the camera and "what is in this box". These tests assert the
 * dispatch body against the {@code libs/contracts} shape the hub forwards, and that the agent's own
 * wording survives both ways (an unknown token is its answer, not a gateway error).
 */
@SpringBootTest(properties = "gateway.telegram.bot-token=")
class MessageProcessorScanTest {

    private static final String LABEL_ID = "demo-b07";

    static MockWebServer profile;
    static MockWebServer orchestrator;
    static MockWebServer media;
    static MockWebServer mediaProcessing;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        profile = new MockWebServer();
        orchestrator = new MockWebServer();
        media = new MockWebServer();
        mediaProcessing = new MockWebServer();
        try {
            profile.start();
            orchestrator.start();
            media.start();
            mediaProcessing.start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start mock server", e);
        }
        r.add("gateway.services.profile-base-url", () -> "http://localhost:" + profile.getPort());
        r.add("gateway.services.orchestrator-base-url", () -> "http://localhost:" + orchestrator.getPort());
        r.add("gateway.services.media-base-url", () -> "http://localhost:" + media.getPort());
        r.add("gateway.services.media-processing-base-url",
                () -> "http://localhost:" + mediaProcessing.getPort());
    }

    @Autowired MessageProcessor processor;
    @Autowired ObjectMapper json;

    @Test
    void aScannedLabelIsDispatchedToInventoryThroughTheHub() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID householdId = UUID.randomUUID();
        profile.enqueue(jsonBody(json.writeValueAsString(new UserDto(
                userId, householdId, "Vlad", "ru", 5L, "admin", Instant.now()))));
        ObjectNode result = json.createObjectNode();
        result.put("message", "Коробка B-07 «кухня — посуда», кладовка: https://card");
        orchestrator.enqueue(jsonBody(json.writeValueAsString(AgentActionResult.ok(result))));

        IntentResponse response = processor.showContainer(incoming(), LABEL_ID).block();

        assertThat(response).isNotNull();
        assertThat(response.agent()).isEqualTo("inventory");
        assertThat(response.text()).contains("B-07").contains("https://card");

        profile.takeRequest(); // identity resolve — the allowlist still governs first contact
        RecordedRequest invoke = orchestrator.takeRequest();
        assertThat(invoke.getPath()).isEqualTo("/v1/agents/invoke");
        JsonNode body = json.readTree(invoke.getBody().readUtf8());
        assertThat(body.path("targetAgent").asString()).isEqualTo("inventory");
        assertThat(body.path("action").asString()).isEqualTo("show_container");
        assertThat(body.path("args").path("qrToken").asString()).isEqualTo(LABEL_ID);
        // The scanner's own tenant travels with the ask, so the card is published under their household.
        assertThat(body.path("householdId").asString()).isEqualTo(householdId.toString());
        assertThat(body.path("userId").asString()).isEqualTo(userId.toString());
    }

    /** The agent owns the wording of "no such box" — the gateway surfaces it, never dresses it up. */
    @Test
    void anUnknownTokensAnswerIsShownVerbatim() throws Exception {
        profile.enqueue(jsonBody(json.writeValueAsString(new UserDto(
                UUID.randomUUID(), UUID.randomUUID(), "Vlad", "ru", 5L, "admin", Instant.now()))));
        orchestrator.enqueue(jsonBody(json.writeValueAsString(
                AgentActionResult.error("Не нашёл коробку по этой этикетке."))));

        IntentResponse response = processor.showContainer(incoming(), "no-such-label").block();

        assertThat(response).isNotNull();
        assertThat(response.text()).isEqualTo("Не нашёл коробку по этой этикетке.");
        profile.takeRequest();
        orchestrator.takeRequest();
    }

    /** inventory is cold or unregistered (the hub answers 404) → a plain notice, never a stack trace. */
    @Test
    void anUnregisteredInventoryDegradesToANotice() throws Exception {
        profile.enqueue(jsonBody(json.writeValueAsString(new UserDto(
                UUID.randomUUID(), UUID.randomUUID(), "Vlad", "ru", 5L, "admin", Instant.now()))));
        orchestrator.enqueue(new MockResponse().setResponseCode(404));

        IntentResponse response = processor.showContainer(incoming(), LABEL_ID).block();

        assertThat(response).isNotNull();
        assertThat(response.text()).isEqualTo(MessageProcessor.SCAN_UNAVAILABLE);
        profile.takeRequest();
        orchestrator.takeRequest();
    }

    /**
     * A caption means the owner is <em>saying</em> something about the picture, so the photo keeps its
     * normal route and is never decoded — otherwise a scan would hijack "добавь сюда ещё одну вещь".
     */
    @Test
    void aCaptionedPhotoIsNeverTreatedAsAScan() throws Exception {
        UUID mediaId = UUID.randomUUID();
        int decodesBefore = mediaProcessing.getRequestCount();
        profile.enqueue(jsonBody(json.writeValueAsString(new UserDto(
                UUID.randomUUID(), UUID.randomUUID(), "Vlad", "ru", 5L, "admin", Instant.now()))));
        media.enqueue(jsonBody(json.writeValueAsString(new MediaObjectDto(
                mediaId, UUID.randomUUID(), null, "image", "image/jpeg", 23L, null,
                "telegram", Instant.now()))));
        orchestrator.enqueue(jsonBody(json.writeValueAsString(
                new IntentResponse("docs", "сохранил", "mock-large"))));

        IntentResponse response = processor.process(photo("что это за коробка")).block();

        assertThat(response).isNotNull();
        assertThat(response.text()).isEqualTo("сохранил");
        assertThat(mediaProcessing.getRequestCount()).isEqualTo(decodesBefore);
        profile.takeRequest();
        media.takeRequest();
        assertThat(orchestrator.takeRequest().getPath()).isEqualTo("/v1/intent");
    }

    /** A photo with no code (a receipt, a wardrobe shot) routes exactly as it did before this path. */
    @Test
    void aPhotoWithNoCodeRoutesNormally() throws Exception {
        UUID mediaId = UUID.randomUUID();
        profile.enqueue(jsonBody(json.writeValueAsString(new UserDto(
                UUID.randomUUID(), UUID.randomUUID(), "Vlad", "ru", 5L, "admin", Instant.now()))));
        media.enqueue(jsonBody(json.writeValueAsString(new MediaObjectDto(
                mediaId, UUID.randomUUID(), null, "image", "image/jpeg", 23L, null,
                "telegram", Instant.now()))));
        mediaProcessing.enqueue(jsonBody(json.writeValueAsString(new QrResult(null, null))));
        orchestrator.enqueue(jsonBody(json.writeValueAsString(
                new IntentResponse("finance", "черновик готов", "mock-large"))));

        IntentResponse response = processor.process(photo(null)).block();

        assertThat(response).isNotNull();
        assertThat(response.text()).isEqualTo("черновик готов");
        profile.takeRequest();
        media.takeRequest();
        mediaProcessing.takeRequest();
        assertThat(orchestrator.takeRequest().getPath()).isEqualTo("/v1/intent");
    }

    /** The decode capability being down must cost a photo nothing — it is not the payload. */
    @Test
    void aFailedDecodeStillRoutesThePhoto() throws Exception {
        UUID mediaId = UUID.randomUUID();
        profile.enqueue(jsonBody(json.writeValueAsString(new UserDto(
                UUID.randomUUID(), UUID.randomUUID(), "Vlad", "ru", 5L, "admin", Instant.now()))));
        media.enqueue(jsonBody(json.writeValueAsString(new MediaObjectDto(
                mediaId, UUID.randomUUID(), null, "image", "image/jpeg", 23L, null,
                "telegram", Instant.now()))));
        mediaProcessing.enqueue(new MockResponse().setResponseCode(503));
        orchestrator.enqueue(jsonBody(json.writeValueAsString(
                new IntentResponse("finance", "черновик готов", "mock-large"))));

        IntentResponse response = processor.process(photo(null)).block();

        assertThat(response).isNotNull();
        assertThat(response.text()).isEqualTo("черновик готов");
        profile.takeRequest();
        media.takeRequest();
        mediaProcessing.takeRequest();
        assertThat(orchestrator.takeRequest().getPath()).isEqualTo("/v1/intent");
    }

    private static MessageProcessor.IncomingMessage incoming() {
        return new MessageProcessor.IncomingMessage(
                5L, "Vlad", "ru", null, MessageScope.PRIVATE, "7");
    }

    private static MessageProcessor.IncomingMessage photo(String caption) {
        return new MessageProcessor.IncomingMessage(
                5L, "Vlad", "ru", caption, MessageScope.PRIVATE, "7",
                new MessageProcessor.IncomingMedia(
                        "fake-jpeg".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "image/jpeg", "photo.jpg", "image"));
    }

    private static MockResponse jsonBody(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
