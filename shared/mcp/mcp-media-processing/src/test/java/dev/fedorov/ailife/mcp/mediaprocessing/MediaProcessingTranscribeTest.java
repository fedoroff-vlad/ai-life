package dev.fedorov.ailife.mcp.mediaprocessing;

import dev.fedorov.ailife.contracts.media.TranscriptResult;
import dev.fedorov.ailife.mcp.mediaprocessing.tools.MediaProcessingMcpTools;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MP-d2a: the {@code transcribe} tool fetches bytes from media-service by object id and
 * runs the STT engine. With the {@link dev.fedorov.ailife.mcp.mediaprocessing.engine.StubSttEngine}
 * default this proves the wiring end-to-end (no native/model dep) — MP-d2b swaps a real
 * whisper engine behind the same interface, so this test keeps holding.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaProcessingTranscribeTest {

    static MockWebServer mediaService;

    @BeforeAll
    static void start() throws Exception {
        mediaService = new MockWebServer();
        mediaService.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mediaService.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("mediaprocessing.media-service-url", () -> "http://localhost:" + mediaService.getPort());
        // Pin both engines to the native-free stubs so the context boots without tesseract
        // or the whisper sidecar, and this asserts the deterministic stub marker.
        r.add("mediaprocessing.ocr-engine", () -> "stub");
        r.add("mediaprocessing.stt-engine", () -> "stub");
    }

    @Autowired
    MediaProcessingMcpTools tools;

    @Test
    void transcribeFetchesMediaByIdAndRunsEngine() throws Exception {
        byte[] body = "fake-audio-bytes".getBytes();
        mediaService.enqueue(new MockResponse()
                .setHeader("content-type", "audio/ogg")
                .setBody(new okio.Buffer().write(body)));

        TranscriptResult result = tools.transcribe("media-789");

        // Stub engine returns a deterministic marker with the byte count → proves the
        // fetched bytes reached the engine.
        assertThat(result.text()).isEqualTo("[stub-stt] " + body.length + " bytes");

        RecordedRequest sent = mediaService.takeRequest();
        assertThat(sent.getPath()).isEqualTo("/v1/media/media-789");
        assertThat(sent.getMethod()).isEqualTo("GET");
    }

    @Test
    void transcribeReturnsEmptyTextWhenNoBytes() throws Exception {
        // Object exists but has no body → no text, not an error.
        mediaService.enqueue(new MockResponse()
                .setHeader("content-type", "audio/ogg"));

        TranscriptResult result = tools.transcribe("media-empty");

        assertThat(result.text()).isEmpty();
        // Drain so the shared static MockWebServer queue stays method-order-independent.
        mediaService.takeRequest();
    }
}
