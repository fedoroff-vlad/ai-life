package dev.fedorov.ailife.tg.bot;

import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.inventory.BoxDeepLink;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.contracts.media.QrInput;
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
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Domain closer for inventory (IN-f2)</b> — proves the photographed-label scan flows
 * {@code gateway → media-service → mcp-media-processing (decode_qr) → orchestrator hub → inventory}
 * through the gateway's <b>real</b> code across HTTP boundaries, and that the {@code libs/contracts}
 * DTOs survive serialisation at every hop: {@link QrInput} out / {@link QrResult} in on the decode, and
 * {@link AgentActionRequest} out / {@link AgentActionResult} in on the hub dispatch.
 *
 * <p>This is the chain the domain exists for and the one no single-seam test covers: a sticker's pixels
 * become a container id, and that id becomes the card. It asserts the <b>bridges</b> rather than
 * string-matching fields — the decoded {@code box_<token>} payload must reappear as
 * {@code args.qrToken} in the hub request, which is where a prefix or parser drift would silently break
 * every already-printed label.
 *
 * <p>The MCP/SSE binding can't be MockWebServer'd, so the decode rides the {@code /internal/qr} HTTP
 * passthrough (the shape every intent-skill flow already uses) and the agent hop rides the hub's
 * {@code /v1/agents/invoke} — both real HTTP.
 */
@SpringBootTest(properties = "gateway.telegram.bot-token=")
class E2EInventoryScanFlowTest {

    // A low-entropy stand-in for an opaque qr_token (a hex-looking literal beside "token" trips gitleaks).
    private static final String LABEL_ID = "demo-b07";

    static MockWebServer profile;
    static MockWebServer media;
    static MockWebServer mediaProcessing;
    static MockWebServer orchestrator;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        profile = new MockWebServer();
        media = new MockWebServer();
        mediaProcessing = new MockWebServer();
        orchestrator = new MockWebServer();
        try {
            profile.start();
            media.start();
            mediaProcessing.start();
            orchestrator.start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start mock servers", e);
        }
        r.add("gateway.services.profile-base-url", () -> "http://localhost:" + profile.getPort());
        r.add("gateway.services.media-base-url", () -> "http://localhost:" + media.getPort());
        r.add("gateway.services.media-processing-base-url", () -> "http://localhost:" + mediaProcessing.getPort());
        r.add("gateway.services.orchestrator-base-url", () -> "http://localhost:" + orchestrator.getPort());
    }

    @Autowired MessageProcessor processor;
    @Autowired ObjectMapper json;

    @Test
    void photographedLabelIsUploadedDecodedAndAnsweredWithTheContainerCard() throws Exception {
        UUID household = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        String card = "Коробка B-07 «кухня — посуда», кладовка, упакована, 2 предмета: https://media/card";

        // Hop 1: identity resolve (returning member — the person unpacking may not be the packer).
        profile.enqueue(jsonBody(json.writeValueAsString(new UserDto(
                userId, household, "vlad", "ru", 99L, "admin", Instant.now()))));
        // Hop 2: media-service stores the photographed sticker, returns its object id.
        media.enqueue(jsonBody(json.writeValueAsString(new MediaObjectDto(
                mediaId, household, userId, "image", "image/jpeg",
                23L, "0ddba11", "telegram", Instant.now()))));
        // Hop 3: mcp-media-processing reads the QR off those bytes → the printed deep link.
        mediaProcessing.enqueue(jsonBody(json.writeValueAsString(new QrResult(
                BoxDeepLink.of("ai_life_bot", LABEL_ID), "QR_CODE"))));
        // Hop 4: the hub relays the action to inventory, which answers with the card.
        var result = json.createObjectNode();
        result.put("message", card);
        result.put("cardUrl", "https://media/card");
        orchestrator.enqueue(jsonBody(json.writeValueAsString(AgentActionResult.ok(result))));

        byte[] photoBytes = "fake-jpeg-bytes-of-a-label".getBytes(StandardCharsets.UTF_8);
        var incoming = new MessageProcessor.IncomingMessage(
                99L, "vlad", "ru", null, MessageScope.PRIVATE, "11",
                new MessageProcessor.IncomingMedia(photoBytes, "image/jpeg", "label.jpg", "image"));

        IntentResponse response = processor.process(incoming).block();

        assertThat(response).isNotNull();
        assertThat(response.agent()).isEqualTo("inventory");
        assertThat(response.text()).isEqualTo(card);

        // Hop 2 asserted: the photo is stored as an image attachment before anything reads it.
        RecordedRequest upload = media.takeRequest();
        assertThat(upload.getPath()).isEqualTo("/v1/media");
        assertThat(upload.getBody().readUtf8()).contains("image");

        // Hop 3 asserted via the CONTRACT: the decode request IS a QrInput carrying the stored media id.
        RecordedRequest decode = mediaProcessing.takeRequest();
        assertThat(decode.getPath()).isEqualTo("/internal/qr");
        QrInput asked = json.readValue(decode.getBody().readUtf8(), QrInput.class);
        assertThat(asked.mediaId()).isEqualTo(mediaId.toString());

        // Hop 4 asserted via the CONTRACT: the hub request IS an AgentActionRequest whose args.qrToken is
        // the token parsed out of the decoded deep link — the pixels→id→card bridge, end to end. And it
        // is an ACTION, not a /v1/intent route: a sticker is never classified.
        RecordedRequest invoke = orchestrator.takeRequest();
        assertThat(invoke.getPath()).isEqualTo("/v1/agents/invoke");
        AgentActionRequest dispatched =
                json.readValue(invoke.getBody().readUtf8(), AgentActionRequest.class);
        assertThat(dispatched.targetAgent()).isEqualTo("inventory");
        assertThat(dispatched.action()).isEqualTo("show_container");
        assertThat(dispatched.args().path("qrToken").asString()).isEqualTo(LABEL_ID);
        assertThat(dispatched.householdId()).isEqualTo(household);
        assertThat(dispatched.userId()).isEqualTo(userId);
    }

    private static MockResponse jsonBody(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
