package dev.fedorov.ailife.mcp.mediaprocessing.http;

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
 * Uploads an extracted video keyframe to media-service ({@code POST /v1/media}, multipart) and returns
 * the catalogue entry, so {@code frames} (MP-e) can hand back the frames' {@code mediaId}s the
 * {@code caption} tool then consumes. The write-side twin of the read-only {@link MediaClient}; kept
 * local because a capability-MCP owns its own thin media-service clients (mirrors {@code mcp-media-fetch}'s
 * {@code MediaStoreClient}; the agent-facing upload lives in {@code agent-runtime}'s {@code MediaStoreClient}
 * for a different caller shape). If a third capability copy appears, lift to a shared {@code libs/media-client}.
 */
@Component
public class MediaStoreClient {

    private static final String SOURCE = "media-processing";

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
