package dev.fedorov.ailife.agents.researcher.http;

import dev.fedorov.ailife.contracts.mediafetch.AudioFetchInput;
import dev.fedorov.ailife.contracts.mediafetch.AudioFetchResult;
import dev.fedorov.ailife.contracts.mediafetch.TranscribeInput;
import dev.fedorov.ailife.contracts.mediafetch.VideoTranscript;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Calls the shared {@code mcp-media-fetch} acquisition capability's {@code /internal/*} passthroughs
 * (the deterministic, MockWebServer-testable path — MCP/SSE can't be mocked). Two cheap-first tiers of
 * the video-understanding flow (V-c): {@code transcribeVideo} reads a video's captions/subtitles (no
 * download — the cheapest content read), and {@code fetchAudio} extracts audio-only into media-service
 * (returning a {@code mediaId}) for the no-captions STT tier.
 *
 * <p>Kept local: researcher is the first consumer of {@code mcp-media-fetch}. Per the shared-client
 * doctrine (architecture.md §outbound clients), the <b>second</b> agent that needs these lifts them into
 * {@code libs/agent-runtime/http}. A yt-dlp subprocess can be slow, so generous timeouts.
 */
@Component
public class MediaFetchClient {

    private final WebClient http;

    public MediaFetchClient(@Qualifier("mcpMediaFetchWebClient") WebClient http) {
        this.http = http;
    }

    /** Tier 1 (link): a video's spoken text from its captions — empty text when it has none. */
    public Mono<VideoTranscript> transcribeVideo(String url, String lang) {
        return http.post()
                .uri("/internal/transcribe")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new TranscribeInput(url, lang))
                .retrieve()
                .bodyToMono(VideoTranscript.class)
                .timeout(Duration.ofSeconds(60));
    }

    /** Tier 2 (link): extract audio-only to media-service; {@code mediaId} is null when none obtained. */
    public Mono<AudioFetchResult> fetchAudio(String url, UUID householdId, UUID ownerId) {
        return http.post()
                .uri("/internal/fetch-audio")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AudioFetchInput(url, householdId, ownerId))
                .retrieve()
                .bodyToMono(AudioFetchResult.class)
                .timeout(Duration.ofSeconds(120));
    }
}
