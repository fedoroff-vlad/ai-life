package dev.fedorov.ailife.mcp.mediaprocessing;

import dev.fedorov.ailife.contracts.media.OcrResult;
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
 * MP-a: the {@code ocr} tool fetches bytes from media-service by object id and runs
 * the engine. With the {@link dev.fedorov.ailife.mcp.mediaprocessing.engine.StubOcrEngine}
 * default this proves the wiring end-to-end (no native dep) — MP-b swaps the real engine
 * behind the same interface, so this test keeps holding.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaProcessingOcrTest {

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
    }

    @Autowired
    MediaProcessingMcpTools tools;

    @Test
    void ocrFetchesMediaByIdAndRunsEngine() throws Exception {
        byte[] body = "fake-image-bytes".getBytes();
        mediaService.enqueue(new MockResponse()
                .setHeader("content-type", "image/jpeg")
                .setBody(new okio.Buffer().write(body)));

        OcrResult result = tools.ocr("media-123");

        // Stub engine returns a deterministic marker with the byte count → proves the
        // fetched bytes reached the engine.
        assertThat(result.text()).isEqualTo("[stub-ocr] " + body.length + " bytes");

        RecordedRequest sent = mediaService.takeRequest();
        assertThat(sent.getPath()).isEqualTo("/v1/media/media-123");
        assertThat(sent.getMethod()).isEqualTo("GET");
    }

    @Test
    void ocrReturnsEmptyTextWhenNoBytes() {
        // Object exists but has no body → no text, not an error.
        mediaService.enqueue(new MockResponse()
                .setHeader("content-type", "image/png"));

        OcrResult result = tools.ocr("media-empty");

        assertThat(result.text()).isEmpty();
    }
}
