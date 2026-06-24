package dev.fedorov.ailife.contracts.creator;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * One generated content piece. Mirrors a {@code creator.content_piece} row — a post {@code kind}
 * (idea | draft); a draft is an idea promoted with {@code body} / {@code cta} / {@code hashtags}.
 * {@code status} is new | kept | posted; {@code trendId} is a soft provenance pointer (no FK — the
 * trend cache is evictable). {@code hashtags} is free-form JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContentPieceDto(
        UUID id,
        UUID householdId,
        UUID ownerId,
        String kind,
        String platform,
        String title,
        String body,
        String cta,
        JsonNode hashtags,
        String status,
        UUID trendId,
        Instant createdAt) {
}
