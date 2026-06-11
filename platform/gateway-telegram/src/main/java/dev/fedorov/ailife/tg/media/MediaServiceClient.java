package dev.fedorov.ailife.tg.media;

import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Uploads an inbound media blob (today: a Telegram photo) to media-service and returns its
 * catalogue entry. The bytes are stored once here; downstream agents fetch them back by
 * {@link MediaObjectDto#id()}. Not soft-failed — for a photo message the upload IS the payload,
 * so a failure should surface as an error reply rather than a silent drop.
 */
@Component
public class MediaServiceClient {

    private final WebClient http;

    public MediaServiceClient(WebClient mediaWebClient) {
        this.http = mediaWebClient;
    }

    public Mono<MediaObjectDto> upload(UUID householdId,
                                       UUID ownerId,
                                       String kind,
                                       String source,
                                       String filename,
                                       String mimeType,
                                       byte[] bytes) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        }).contentType(safeMediaType(mimeType));
        body.part("householdId", householdId.toString());
        if (ownerId != null) body.part("ownerId", ownerId.toString());
        if (kind != null) body.part("kind", kind);
        if (source != null) body.part("source", source);

        return http.post().uri("/v1/media")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build()))
                .retrieve()
                .bodyToMono(MediaObjectDto.class);
    }

    private static MediaType safeMediaType(String mimeType) {
        try {
            return MediaType.parseMediaType(mimeType);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
