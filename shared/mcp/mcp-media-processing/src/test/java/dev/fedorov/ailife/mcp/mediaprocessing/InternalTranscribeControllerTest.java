package dev.fedorov.ailife.mcp.mediaprocessing;

import dev.fedorov.ailife.contracts.media.TranscribeInput;
import dev.fedorov.ailife.contracts.media.TranscriptResult;
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
 * The {@code POST /internal/transcribe} HTTP passthrough drives the same fetch → STT-engine logic as
 * the MCP {@code transcribe} tool, but over a MockWebServer-testable transport gateway-telegram calls
 * deterministically (the MCP/SSE transport can't be mocked). Runs on the stub STT engine so no
 * whisper sidecar is needed — asserts the media fetch + the tool result reaching the caller. STT twin
 * of {@link InternalOcrControllerTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class InternalTranscribeControllerTest {

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
        // Use the native-free stub STT engine (marker text) so the test runs everywhere.
        r.add("mediaprocessing.stt-engine", () -> "stub");
    }

    @Autowired WebTestClient web;

    @Test
    void passthroughFetchesAudioAndRunsStt() throws Exception {
        byte[] bytes = "voice-bytes".getBytes();
        mediaService.enqueue(new MockResponse()
                .setHeader("content-type", "audio/ogg")
                .setBody(new okio.Buffer().write(bytes)));

        TranscriptResult result = web.post().uri("/internal/transcribe")
                .bodyValue(new TranscribeInput("media-77"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(TranscriptResult.class)
                .returnResult()
                .getResponseBody();

        assertThat(result).isNotNull();
        // StubSttEngine returns a deterministic byte-count marker.
        assertThat(result.text()).isEqualTo("[stub-stt] " + bytes.length + " bytes");

        RecordedRequest mediaReq = mediaService.takeRequest();
        assertThat(mediaReq.getPath()).isEqualTo("/v1/media/media-77");
    }
}
