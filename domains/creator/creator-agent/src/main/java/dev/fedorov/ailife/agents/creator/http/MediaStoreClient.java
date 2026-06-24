package dev.fedorov.ailife.agents.creator.http;

import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Uploads the rendered content-plan HTML to media-service and returns its catalogue entry, so the
 * content-strategist flow can hand the user a stable link to open on any device. Mirrors the
 * nutritionist's {@code MediaStoreClient} (multipart {@code POST /v1/media}). The stored MIME type is
 * the renderer's ({@code text/html}), so opening the link renders the page.
 */
@Component
public class MediaStoreClient {

    private final WebClient http;

    public MediaStoreClient(@Qualifier("mediaServiceWebClient") WebClient http) {
        this.http = http;
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
        body.part("source", "creator");

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
