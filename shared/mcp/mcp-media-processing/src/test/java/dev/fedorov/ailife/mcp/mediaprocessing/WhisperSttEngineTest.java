package dev.fedorov.ailife.mcp.mediaprocessing;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.media.TranscriptResult;
import dev.fedorov.ailife.mcp.mediaprocessing.engine.WhisperSttEngine;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MP-d2b: {@link WhisperSttEngine} talks to the whisper ASR sidecar over plain HTTP, so it's
 * fully testable with a MockWebServer — no native lib, no model, runs in CI like any other
 * HTTP client. (The live whisper container is exercised via {@code docker compose up}, the way
 * SearXNG/mcp-web is live-verified, not in CI.)
 */
class WhisperSttEngineTest {

    MockWebServer asr;
    WhisperSttEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        asr = new MockWebServer();
        asr.start();
        WebClient client = WebClient.builder().baseUrl("http://localhost:" + asr.getPort()).build();
        engine = new WhisperSttEngine(client, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws Exception {
        asr.shutdown();
    }

    @Test
    void postsAudioToAsrAndParsesTextLanguageDuration() throws Exception {
        asr.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("""
                        {"text":" привет мир ","language":"ru",
                         "segments":[{"end":1.2},{"end":3.7}]}
                        """));

        TranscriptResult result = engine.transcribe("audio-bytes".getBytes(), "audio/ogg");

        assertThat(result.text()).isEqualTo("привет мир");   // strip()ed
        assertThat(result.lang()).isEqualTo("ru");
        assertThat(result.durationSeconds()).isEqualTo(3.7); // last segment end

        RecordedRequest sent = asr.takeRequest();
        assertThat(sent.getMethod()).isEqualTo("POST");
        assertThat(sent.getPath()).startsWith("/asr");
        assertThat(sent.getPath()).contains("output=json");
        assertThat(sent.getHeader("content-type")).startsWith("multipart/form-data");
    }

    @Test
    void noSpeechYieldsEmptyTextNotAnError() {
        asr.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("{\"text\":\"\"}"));

        TranscriptResult result = engine.transcribe("silence".getBytes(), "audio/ogg");

        assertThat(result.text()).isEmpty();
        assertThat(result.lang()).isNull();
    }

    @Test
    void emptyBytesSkipTheCall() {
        // No enqueued response — engine must not hit the sidecar at all.
        TranscriptResult result = engine.transcribe(new byte[0], "audio/ogg");

        assertThat(result.text()).isEmpty();
        assertThat(asr.getRequestCount()).isZero();
    }

    @Test
    void sidecarFailureSurfacesAsIllegalState() {
        asr.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> engine.transcribe("audio".getBytes(), "audio/ogg"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STT failed");
    }
}
