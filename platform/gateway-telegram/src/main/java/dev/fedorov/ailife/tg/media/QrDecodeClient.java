package dev.fedorov.ailife.tg.media;

import dev.fedorov.ailife.contracts.media.QrInput;
import dev.fedorov.ailife.contracts.media.QrResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Reads a barcode out of an uploaded photo via mcp-media-processing's {@code POST /internal/qr}
 * passthrough (the deterministic, MockWebServer-testable transport, not the un-mockable MCP/SSE
 * binding). The barcode twin of {@link TranscribeClient}: <b>front-door decode</b>, because a
 * captionless photo has no text for the orchestrator to classify, so a scanned container label can
 * only be recognised <em>before</em> routing (inventory IN-f2).
 *
 * <p>Unlike STT this is <b>soft-failed</b>, and the difference is the point: for a voice note the
 * transcript IS the payload, while here the photo already has a perfectly good route. A dead capability,
 * a slow one, or a picture with no code must therefore cost nothing — the photo goes on to the
 * orchestrator exactly as before.
 */
@Component
public class QrDecodeClient {

    private static final Logger log = LoggerFactory.getLogger(QrDecodeClient.class);

    /** Short on purpose: this sits in front of every captionless photo, including receipts. */
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final WebClient http;

    public QrDecodeClient(WebClient mediaProcessingWebClient) {
        this.http = mediaProcessingWebClient;
    }

    /**
     * The decoded payload of the first code in the image, or {@link Mono#empty()} when there is none —
     * including when the capability itself failed (logged at debug, never surfaced).
     *
     * @param mediaId media-service object id of the stored photo (an attachment's storageUri).
     */
    public Mono<String> decode(String mediaId) {
        return http.post().uri("/internal/qr")
                .bodyValue(new QrInput(mediaId))
                .retrieve()
                .bodyToMono(QrResult.class)
                .timeout(TIMEOUT)
                .flatMap(result -> result.payload() == null || result.payload().isBlank()
                        ? Mono.empty()
                        : Mono.just(result.payload()))
                .onErrorResume(e -> {
                    log.debug("QR decode skipped for media {}: {}", mediaId, e.toString());
                    return Mono.empty();
                });
    }
}
