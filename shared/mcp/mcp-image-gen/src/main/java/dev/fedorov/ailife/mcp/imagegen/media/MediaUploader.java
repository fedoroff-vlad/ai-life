package dev.fedorov.ailife.mcp.imagegen.media;

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
 * Stores a generated image in media-service ({@code POST /v1/media} multipart) and returns its
 * catalogue entry, so the capability hands callers a stable media id (like any other media object).
 * Mirrors gateway-telegram's media upload.
 */
@Component
public class MediaUploader {

    private final WebClient http;

    public MediaUploader(@Qualifier("mediaServiceWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<MediaObjectDto> upload(UUID householdId, UUID ownerId, String mimeType, byte[] bytes) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "generated.png";
            }
        }).contentType(safeMediaType(mimeType));
        body.part("householdId", householdId.toString());
        if (ownerId != null) body.part("ownerId", ownerId.toString());
        body.part("kind", "image");
        body.part("source", "image-gen");

        return http.post().uri("/v1/media")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build()))
                .retrieve()
                .bodyToMono(MediaObjectDto.class)
                .timeout(Duration.ofSeconds(20));
    }

    private static MediaType safeMediaType(String mimeType) {
        try {
            return MediaType.parseMediaType(mimeType);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
