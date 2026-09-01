package dev.fedorov.ailife.mcp.mediafetch.http;

import dev.fedorov.ailife.contracts.media.MediaObjectDto;
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
 * Uploads extracted audio bytes to media-service ({@code POST /v1/media}, multipart) and returns the
 * catalogue entry, so {@code fetch_audio} can hand back a {@code mediaId} the media-processing tools
 * consume for STT. Kept local: a capability-MCP owns its own thin media-service clients (mirrors
 * {@code mcp-media-processing}'s read-only {@code MediaClient}; the agent-facing upload lives in
 * {@code agent-runtime}'s {@code MediaStoreClient} for a different caller shape). If a third copy
 * appears, lift to a shared {@code libs/media-client}.
 */
@Component
public class MediaStoreClient {

    private static final String SOURCE = "media-fetch";

    private final WebClient http;

    public MediaStoreClient(WebClient mediaWebClient) {
        this.http = mediaWebClient;
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
        body.part("source", SOURCE);

        return http.post().uri("/v1/media")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build()))
                .retrieve()
                .bodyToMono(MediaObjectDto.class)
                .timeout(Duration.ofSeconds(30));
    }

    private static MediaType safeMediaType(String mimeType) {
        try {
            return MediaType.parseMediaType(mimeType);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
