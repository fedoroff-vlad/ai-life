package dev.fedorov.ailife.mcp.mediafetch;

import dev.fedorov.ailife.contracts.mediafetch.AudioFetchInput;
import dev.fedorov.ailife.contracts.mediafetch.AudioFetchResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code POST /internal/fetch-audio} passthrough → {@code fetch_audio} tool →
 * {@code AudioFetchEngine} → media-service upload wiring, proved with the native-free <b>stub</b> audio
 * engine ({@code audio-engine=stub}, deterministic marker bytes) and a MockWebServer standing in for
 * media-service — so no yt-dlp / real media-service is needed. Asserts the extracted audio is uploaded
 * (multipart {@code POST /v1/media}) and the returned {@code mediaId} reaches the caller. The real
 * yt-dlp path is exercised manually (like the transcript / OCR real tests). MockWebServer twin of
 * {@link InternalTranscribeControllerTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class InternalFetchAudioControllerTest {

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
        r.add("media-fetch.media-service-url", () -> "http://localhost:" + mediaService.getPort());
        // Native-free stub audio engine (marker bytes) so the test runs everywhere.
        r.add("media-fetch.audio-engine", () -> "stub");
        // The wiring test also stubs the transcript engine (no yt-dlp for the co-located tool bean).
        r.add("media-fetch.transcript-engine", () -> "stub");
    }

    @Autowired WebTestClient web;

    @Test
    void passthroughUploadsAudioAndReturnsMediaId() throws Exception {
        UUID mediaId = UUID.randomUUID();
        UUID household = UUID.randomUUID();
        mediaService.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("""
                        {"id":"%s","householdId":"%s","kind":"file","mimeType":"audio/mp4",
                         "sizeBytes":12,"source":"media-fetch"}
                        """.formatted(mediaId, household)));

        AudioFetchResult result = web.post().uri("/internal/fetch-audio")
                .bodyValue(new AudioFetchInput("https://youtube.com/watch?v=abc", household, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AudioFetchResult.class)
                .returnResult()
                .getResponseBody();

        assertThat(result).isNotNull();
        assertThat(result.mediaId()).isEqualTo(mediaId.toString());
        assertThat(result.source()).isEqualTo("stub");

        RecordedRequest upload = mediaService.takeRequest();
        assertThat(upload.getPath()).isEqualTo("/v1/media");
        assertThat(upload.getMethod()).isEqualTo("POST");
        assertThat(upload.getHeader("content-type")).startsWith("multipart/form-data");
        assertThat(upload.getBody().readUtf8()).contains("[stub-audio]").contains(household.toString());
    }

    @Test
    void missingHouseholdReturnsEmptyWithoutUpload() {
        int before = mediaService.getRequestCount();
        AudioFetchResult result = web.post().uri("/internal/fetch-audio")
                .bodyValue(new AudioFetchInput("https://youtube.com/watch?v=abc", null, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AudioFetchResult.class)
                .returnResult()
                .getResponseBody();

        assertThat(result).isNotNull();
        assertThat(result.mediaId()).isNull();
        assertThat(mediaService.getRequestCount()).isEqualTo(before);
    }
}
