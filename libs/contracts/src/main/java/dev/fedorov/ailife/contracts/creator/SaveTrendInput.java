package dev.fedorov.ailife.contracts.creator;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Save a gathered trend into the cache. Only {@code householdId} + {@code title} are required;
 * {@code capturedAt} defaults to now. {@code ownerId} attributes it to a person (null =
 * household-shared). {@code source} is the origin (web | youtube | reddit | telegram | rss),
 * {@code metrics} the free-form per-source signal.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SaveTrendInput(
        UUID householdId,
        UUID ownerId,
        String source,
        String platform,
        String title,
        String url,
        String summary,
        JsonNode metrics,
        Instant capturedAt) {
}
