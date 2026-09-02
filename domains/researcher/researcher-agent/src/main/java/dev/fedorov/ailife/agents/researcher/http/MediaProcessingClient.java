package dev.fedorov.ailife.agents.researcher.http;

import dev.fedorov.ailife.contracts.media.FramesInput;
import dev.fedorov.ailife.contracts.media.FramesResult;
import dev.fedorov.ailife.contracts.media.TranscribeInput;
import dev.fedorov.ailife.contracts.media.TranscriptResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Calls the shared {@code mcp-media-processing} understanding capability's {@code /internal/*}
 * passthroughs for the id-based tiers of the video-understanding flow (V-c): {@code transcribe} runs STT
 * on a stored audio/video object (whisper decodes the video container itself), and {@code frames}
 * extracts evenly-spaced keyframes (MP-e) as image ids for the visual tier — each then captioned via the
 * shared {@code CaptionClient} (agent-runtime). {@code caption} itself is NOT duplicated here: it already
 * has a shared client, reused not re-embedded.
 *
 * <p>Kept local: researcher is the first domain-agent to need {@code transcribe}/{@code frames}. Per the
 * shared-client doctrine, the <b>second</b> agent lifts them into {@code libs/agent-runtime/http}. STT and
 * ffmpeg keyframe extraction are slow, so generous timeouts.
 */
@Component
public class MediaProcessingClient {

    private final WebClient http;

    public MediaProcessingClient(@Qualifier("mcpMediaProcessingWebClient") WebClient http) {
        this.http = http;
    }

    /** STT on a stored audio/video object — empty text when no speech is recognised. */
    public Mono<TranscriptResult> transcribe(String mediaId) {
        return http.post()
                .uri("/internal/transcribe")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new TranscribeInput(mediaId))
                .retrieve()
                .bodyToMono(TranscriptResult.class)
                .timeout(Duration.ofSeconds(120));
    }

    /** Extract {@code n} evenly-spaced keyframes as stored images — empty list when none produced. */
    public Mono<FramesResult> frames(String mediaId, int n, UUID householdId, UUID ownerId) {
        return http.post()
                .uri("/internal/frames")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new FramesInput(mediaId, n, householdId, ownerId))
                .retrieve()
                .bodyToMono(FramesResult.class)
                .timeout(Duration.ofSeconds(120));
    }
}
