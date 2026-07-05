package dev.fedorov.ailife.media.domain;

import tools.jackson.databind.JsonNode;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code media.media_object}. {@code bucket} / {@code storageKey} are internal storage
 * coordinates and never leave the service — {@link #toDto()} drops them so downstream callers only
 * ever see the object {@code id}.
 */
public record MediaRow(
        UUID id,
        UUID householdId,
        UUID ownerId,
        String kind,
        String mimeType,
        long sizeBytes,
        String sha256,
        String bucket,
        String storageKey,
        String source,
        JsonNode metadata,
        Instant createdAt) {

    public MediaObjectDto toDto() {
        return new MediaObjectDto(
                id, householdId, ownerId, kind, mimeType, sizeBytes, sha256, source, createdAt);
    }
}
