package dev.fedorov.ailife.contracts.creator;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * One cached trend. Mirrors a {@code creator.trend} row — a trend the agent gathered from a source
 * (web | youtube | reddit | telegram | rss) and kept for provenance / reuse. {@code metrics} is the
 * free-form per-source signal (score / engagement) when available; {@code url} is the source link.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TrendDto(
        UUID id,
        UUID householdId,
        UUID ownerId,
        String source,
        String platform,
        String title,
        String url,
        String summary,
        JsonNode metrics,
        Instant capturedAt,
        Instant createdAt) {
}
