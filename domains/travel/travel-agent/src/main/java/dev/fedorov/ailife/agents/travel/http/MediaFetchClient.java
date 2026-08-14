package dev.fedorov.ailife.agents.travel.http;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Fetches the raw bytes of a stored media object from media-service ({@code GET /v1/media/{id}}). A route
 * file arrives as a {@code file} attachment whose {@code storageUri} is the media object id; the route flow
 * (RT-c) fetches the bytes back here, then sniffs the format and imports. Reuses the shared
 * {@code mediaServiceWebClient} (the same base the board upload uses).
 */
@Component
public class MediaFetchClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WebClient http;

    public MediaFetchClient(@Qualifier("mediaServiceWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<byte[]> fetch(String mediaId) {
        return http.get().uri("/v1/media/{id}", mediaId)
                .retrieve().bodyToMono(byte[].class).timeout(TIMEOUT);
    }
}
