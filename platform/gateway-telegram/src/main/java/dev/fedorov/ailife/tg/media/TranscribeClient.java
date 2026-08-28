package dev.fedorov.ailife.tg.media;

import dev.fedorov.ailife.contracts.media.TranscribeInput;
import dev.fedorov.ailife.contracts.media.TranscriptResult;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Turns an uploaded voice note into text via mcp-media-processing's {@code POST /internal/transcribe}
 * passthrough (the deterministic, MockWebServer-testable transport, not the un-mockable MCP/SSE
 * binding). Front-door STT: the gateway transcribes before the orchestrator routes, so a spoken
 * request reaches any agent as ordinary text. Not soft-failed — for a voice message the transcript
 * IS the payload, so a failure surfaces as an error reply rather than a silent empty route.
 */
@Component
public class TranscribeClient {

    private final WebClient http;

    public TranscribeClient(WebClient mediaProcessingWebClient) {
        this.http = mediaProcessingWebClient;
    }

    /**
     * Returns the full {@link TranscriptResult} (not just the text) so the caller can apply the RU-3
     * reliability gate on {@code confidence} — an empty or low-confidence transcript is asked to be
     * repeated rather than routed as garbage (#489 RU-3).
     *
     * @param mediaId media-service object id of the stored audio (an attachment's storageUri).
     */
    public Mono<TranscriptResult> transcribe(String mediaId) {
        return http.post().uri("/internal/transcribe")
                .bodyValue(new TranscribeInput(mediaId))
                .retrieve()
                .bodyToMono(TranscriptResult.class);
    }
}
