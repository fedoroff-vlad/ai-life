package dev.fedorov.ailife.agents.finance.http;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Fetches raw media bytes from media-service by object id (the {@code storageUri} an inbound
 * attachment carries). Used by {@code MoneyProCsvImporter} to pull an uploaded CSV back before
 * the import (the receipt flow no longer fetches bytes here — the {@code mcp-media-processing}
 * capability does that for the {@code caption} call, MP-c). The body is read fully into memory —
 * uploads are size-capped by media-service, so this is bounded.
 */
@Component
public class MediaClient {

    private final WebClient http;

    public MediaClient(@Qualifier("mediaServiceWebClient") WebClient http) {
        this.http = http;
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
