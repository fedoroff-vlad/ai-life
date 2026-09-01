package dev.fedorov.ailife.mcp.mediaprocessing;

import dev.fedorov.ailife.contracts.media.FramesInput;
import dev.fedorov.ailife.contracts.media.FramesResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MP-e: the {@code POST /internal/frames} passthrough → {@code frames} tool → {@code FrameExtractor} →
 * media-service upload wiring, proved with the native-free <b>stub</b> extractor
 * ({@code frame-extractor=stub}, deterministic marker frames) and a MockWebServer standing in for
 * media-service — so no ffmpeg / real media-service is needed. Asserts the source video is fetched by id,
 * each extracted frame is uploaded (multipart {@code POST /v1/media}), and the stored ids reach the
 * caller in order. The real ffmpeg path is exercised manually (like the whisper / tesseract real tests).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class MediaProcessingFramesTest {

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
        // Pin every engine to the native-free stubs so the context boots without tesseract, whisper
        // or ffmpeg, and this asserts the deterministic stub wiring.
        r.add("mediaprocessing.ocr-engine", () -> "stub");
        r.add("mediaprocessing.stt-engine", () -> "stub");
        r.add("mediaprocessing.frame-extractor", () -> "stub");
    }

    @Autowired
    WebTestClient web;

    @Test
    void passthroughExtractsFramesUploadsThemAndReturnsIds() throws Exception {
        UUID household = UUID.randomUUID();
        UUID f0 = UUID.randomUUID();
        UUID f1 = UUID.randomUUID();
        UUID f2 = UUID.randomUUID();

        // 1) source video GET, then 2) three frame uploads (in order).
        mediaService.enqueue(new MockResponse()
                .setHeader("content-type", "video/mp4")
                .setBody(new okio.Buffer().write("fake-video-bytes".getBytes())));
        for (UUID id : new UUID[]{f0, f1, f2}) {
            mediaService.enqueue(new MockResponse()
                    .setHeader("content-type", "application/json")
                    .setBody("""
                            {"id":"%s","householdId":"%s","kind":"file","mimeType":"image/jpeg",
                             "sizeBytes":10,"source":"media-processing"}
                            """.formatted(id, household)));
        }

        FramesResult result = web.post().uri("/internal/frames")
                .bodyValue(new FramesInput("video-123", 3, household, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(FramesResult.class)
                .returnResult()
                .getResponseBody();

        assertThat(result).isNotNull();
        assertThat(result.frameMediaIds())
                .containsExactly(f0.toString(), f1.toString(), f2.toString());

        RecordedRequest fetch = mediaService.takeRequest();
        assertThat(fetch.getMethod()).isEqualTo("GET");
        assertThat(fetch.getPath()).isEqualTo("/v1/media/video-123");
        for (int i = 0; i < 3; i++) {
            RecordedRequest upload = mediaService.takeRequest();
            assertThat(upload.getMethod()).isEqualTo("POST");
            assertThat(upload.getPath()).isEqualTo("/v1/media");
            assertThat(upload.getHeader("content-type")).startsWith("multipart/form-data");
            assertThat(upload.getBody().readUtf8())
                    .contains("[stub-frame]").contains(household.toString());
        }
    }

    @Test
    void missingHouseholdReturnsEmptyWithoutFetch() {
        int before = mediaService.getRequestCount();
        FramesResult result = web.post().uri("/internal/frames")
                .bodyValue(new FramesInput("video-123", 3, null, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(FramesResult.class)
                .returnResult()
                .getResponseBody();

        assertThat(result).isNotNull();
        assertThat(result.frameMediaIds()).isEmpty();
        assertThat(mediaService.getRequestCount()).isEqualTo(before);
    }
}
