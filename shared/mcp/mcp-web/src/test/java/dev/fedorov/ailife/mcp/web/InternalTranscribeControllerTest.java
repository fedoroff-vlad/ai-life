package dev.fedorov.ailife.mcp.web;

import dev.fedorov.ailife.contracts.web.TranscribeInput;
import dev.fedorov.ailife.contracts.web.VideoTranscript;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V-a: the {@code POST /internal/transcribe} passthrough → {@code transcribe_video} tool →
 * {@code VideoTranscriptEngine} wiring, proved with the native-free <b>stub</b> engine
 * ({@code transcript-engine=stub}) so no yt-dlp / network is needed. The real yt-dlp path is
 * exercised manually (like the OCR engine's real test).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "mcp-web.transcript-engine=stub")
@AutoConfigureWebTestClient
class InternalTranscribeControllerTest {

    @Autowired WebTestClient web;

    @Test
    void passthroughReachesTheTranscriptEngine() {
        VideoTranscript result = web.post().uri("/internal/transcribe")
                .bodyValue(new TranscribeInput("https://youtube.com/watch?v=abc", null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(VideoTranscript.class)
                .returnResult().getResponseBody();

        assertThat(result).isNotNull();
        assertThat(result.url()).isEqualTo("https://youtube.com/watch?v=abc");
        assertThat(result.text()).contains("[stub-transcript]").contains("youtube.com");
    }
}
