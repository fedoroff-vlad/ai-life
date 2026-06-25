package dev.fedorov.ailife.agentruntime.http;

import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Shared media-service upload client for agents that hand the user a rendered deliverable (an HTML
 * board / report). Multipart {@code POST /v1/media} returns the catalogue entry, so the agent can
 * build a stable link to open on any device. The stored MIME type is the renderer's
 * ({@code text/html}), so opening the link renders the page.
 *
 * <p>The {@link WebClient} bean named {@code mediaServiceWebClient} and the {@code source} tag (the
 * owning agent, e.g. {@code creator} / {@code stylist}) are supplied per agent via the {@code @Bean}
 * in that agent's {@code OutboundHttpConfig} — so the {@link #upload} signature stays the same across
 * every caller.
 */
public class MediaStoreClient {

    private final WebClient http;
    private final String source;

    public MediaStoreClient(@Qualifier("mediaServiceWebClient") WebClient http, String source) {
        this.http = http;
        this.source = source;
    }

    public Mono<MediaObjectDto> upload(UUID householdId, UUID ownerId, String filename,
                                       String mimeType, byte[] bytes) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        }).contentType(safeMediaType(mimeType));
        body.part("householdId", householdId.toString());
        if (ownerId != null) body.part("ownerId", ownerId.toString());
        body.part("kind", "file");
        body.part("source", source);

        return http.post().uri("/v1/media")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build()))
                .retrieve()
                .bodyToMono(MediaObjectDto.class)
                .timeout(Duration.ofSeconds(15));
    }

    private static MediaType safeMediaType(String mimeType) {
        try {
            return MediaType.parseMediaType(mimeType);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
