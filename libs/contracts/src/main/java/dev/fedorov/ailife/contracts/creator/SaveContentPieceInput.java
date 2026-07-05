package dev.fedorov.ailife.contracts.creator;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Save a generated content piece (idea or draft). {@code householdId} + {@code kind} (idea | draft)
 * are required; {@code status} defaults to {@code new} when omitted. {@code ownerId} attributes it to
 * a person (null = household-shared). {@code body} / {@code cta} / {@code hashtags} carry a draft's
 * full content (an idea may leave them empty); {@code trendId} is the optional provenance pointer.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SaveContentPieceInput(
        UUID householdId,
        UUID ownerId,
        String kind,
        String platform,
        String title,
        String body,
        String cta,
        JsonNode hashtags,
        String status,
        UUID trendId) {
}
