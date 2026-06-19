package dev.fedorov.ailife.mcp.mediaprocessing.http;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Fetches raw media bytes from media-service by object id ({@code GET /v1/media/{id}}).
 * Callers pass an id, never raw bytes — the capability stays stateless and never
 * stores blobs itself. The body is read fully into memory; media-service caps upload
 * size, so this is bounded.
 *
 * <p>Mirrors the per-agent {@code MediaClient}s (finance-agent's receipt flow); kept
 * local because a capability-MCP owns its own thin clients. If a third copy appears,
 * lift to a shared {@code libs/media-client}.
 */
@Component
public class MediaClient {

    private final WebClient http;

    public MediaClient(WebClient mediaWebClient) {
        this.http = mediaWebClient;
    }

    public Mono<FetchedMedia> fetch(String mediaId) {
        return http.get().uri("/v1/media/{id}", mediaId)
                .exchangeToMono(resp -> {
                    String mime = resp.headers().contentType()
                            .map(MediaType::toString)
                            .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
                    return resp.bodyToMono(byte[].class)
                            .map(bytes -> new FetchedMedia(mime, bytes));
                })
                .timeout(Duration.ofSeconds(5));
    }

    /** Raw bytes of a stored object plus the MIME type to interpret them as. */
    public record FetchedMedia(String mimeType, byte[] bytes) {
    }
}
