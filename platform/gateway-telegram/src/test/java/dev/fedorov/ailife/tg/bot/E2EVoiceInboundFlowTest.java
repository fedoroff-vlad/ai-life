package dev.fedorov.ailife.tg.bot;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.contracts.media.TranscribeInput;
import dev.fedorov.ailife.contracts.media.TranscriptResult;
import dev.fedorov.ailife.contracts.profile.UserDto;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inbound E2E closer for the <b>voice</b> path — proves a voice note flows
 * {@code gateway → media-service → mcp-media-processing (transcribe) → orchestrator} through the
 * gateway's <b>real</b> code across HTTP boundaries, and that the {@code libs/contracts} wire DTOs
 * added with the front-door-STT slice ({@link TranscribeInput} out, {@link TranscriptResult} in)
 * survive serialisation each way. This is the mandated E2E for that cross-service contract — a
 * serialisation or wiring regression on the voice path fails here, not silently in production.
 *
 * <p>Complements {@link MessageProcessorTest} (per-seam behaviour): this one asserts the contract
 * <em>bridges</em> — it parses the transcribe request body back into a {@link TranscribeInput} and
 * the routed message back into a {@link NormalizedMessage}, rather than string-matching fields.
 */
@SpringBootTest(properties = "gateway.telegram.bot-token=")
class E2EVoiceInboundFlowTest {

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
    void voiceNoteIsUploadedTranscribedAndRoutedAsText() throws Exception {
        UUID household = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        String transcript = "запиши что я купил кофе за двести рублей";

        // Hop 1: identity resolve (returning user).
        profile.enqueue(jsonBody(json.writeValueAsString(new UserDto(
                userId, household, "vlad", "ru", 99L, "admin", Instant.now()))));
        // Hop 2: media-service stores the uploaded audio, returns its object id.
        media.enqueue(jsonBody(json.writeValueAsString(new MediaObjectDto(
                mediaId, household, userId, "voice", "audio/ogg",
                64L, "0ddba11", "telegram", Instant.now()))));
        // Hop 3: mcp-media-processing transcribes the stored audio (STT).
        mediaProcessing.enqueue(jsonBody(json.writeValueAsString(
                new TranscriptResult(transcript, "ru", 3.4))));
        // Hop 4: orchestrator routes the transcript like any typed message.
        orchestrator.enqueue(jsonBody(json.writeValueAsString(
                new IntentResponse("finance", "записал", "mock-large"))));

        byte[] voiceBytes = "fake-ogg-opus-bytes".getBytes(StandardCharsets.UTF_8);
        var incoming = new MessageProcessor.IncomingMessage(
                99L, "vlad", "ru", null, MessageScope.PRIVATE, "9",
                new MessageProcessor.IncomingMedia(voiceBytes, "audio/ogg", "voice-abc.oga", "voice"));

        IntentResponse response = processor.process(incoming).block();

        assertThat(response).isNotNull();
        assertThat(response.text()).isEqualTo("записал");

        // Hop 2 asserted: audio uploaded to media-service as a voice attachment.
        RecordedRequest upload = media.takeRequest();
        assertThat(upload.getPath()).isEqualTo("/v1/media");
        assertThat(upload.getBody().readUtf8()).contains("voice");

        // Hop 3 asserted via the CONTRACT: the transcribe request body IS a TranscribeInput carrying
        // the uploaded media id (the storageUri) — the gateway→mcp-media-processing wire bridge.
        RecordedRequest transcribe = mediaProcessing.takeRequest();
        assertThat(transcribe.getPath()).isEqualTo("/internal/transcribe");
        TranscribeInput sent = json.readValue(transcribe.getBody().readUtf8(), TranscribeInput.class);
        assertThat(sent.mediaId()).isEqualTo(mediaId.toString());

        // Hop 4 asserted via the CONTRACT: the orchestrator receives a NormalizedMessage whose text is
        // the transcript (TranscriptResult.text bridged into it) with the voice attachment riding along.
        RecordedRequest routed = orchestrator.takeRequest();
        assertThat(routed.getPath()).isEqualTo("/v1/intent");
        NormalizedMessage msg = json.readValue(routed.getBody().readUtf8(), NormalizedMessage.class);
        assertThat(msg.text()).isEqualTo(transcript);
        assertThat(msg.attachments()).singleElement()
                .satisfies(a -> {
                    assertThat(a.kind()).isEqualTo("voice");
                    assertThat(a.storageUri()).isEqualTo(mediaId.toString());
                });
    }

    private static MockResponse jsonBody(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
