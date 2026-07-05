package dev.fedorov.ailife.mcp.mediaprocessing;

import dev.fedorov.ailife.contracts.media.OcrInput;
import dev.fedorov.ailife.contracts.media.OcrResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-b: the {@code POST /internal/ocr} HTTP passthrough drives the same fetch → OCR-engine logic as
 * the MCP {@code ocr} tool, but over a MockWebServer-testable transport docs-agent calls
 * deterministically (the MCP/SSE transport can't be mocked). Runs on the stub OCR engine so no
 * native tesseract is needed — asserts the media fetch + the tool result reaching the caller.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class InternalOcrControllerTest {

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
        // Use the native-free stub OCR engine (marker text) so the test runs everywhere.
        r.add("mediaprocessing.ocr-engine", () -> "stub");
    }

    @Autowired WebTestClient web;

    @Test
    void passthroughFetchesImageAndRunsOcr() throws Exception {
        byte[] bytes = "img-bytes".getBytes();
        mediaService.enqueue(new MockResponse()
                .setHeader("content-type", "image/jpeg")
                .setBody(new okio.Buffer().write(bytes)));

        OcrResult result = web.post().uri("/internal/ocr")
                .bodyValue(new OcrInput("media-42"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(OcrResult.class)
                .returnResult()
                .getResponseBody();

        assertThat(result).isNotNull();
        // StubOcrEngine returns a deterministic byte-count marker.
        assertThat(result.text()).isEqualTo("[stub-ocr] " + bytes.length + " bytes");

        RecordedRequest mediaReq = mediaService.takeRequest();
        assertThat(mediaReq.getPath()).isEqualTo("/v1/media/media-42");
    }
}
