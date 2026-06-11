package dev.fedorov.ailife.contracts.media;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata for a stored media object. The bytes themselves live in MinIO (object store);
 * this row is the catalogue entry. Downstream callers reference an object purely by {@code id}
 * — they GET {@code /v1/media/{id}} for the raw bytes and never touch the bucket/key directly,
 * so the storage layout stays an internal concern of media-service.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MediaObjectDto(
        UUID id,
        UUID householdId,
        UUID ownerId,
        String kind,
        String mimeType,
        long sizeBytes,
        String sha256,
        String source,
        Instant createdAt) {
}
