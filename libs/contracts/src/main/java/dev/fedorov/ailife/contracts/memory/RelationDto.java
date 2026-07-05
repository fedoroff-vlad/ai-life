package dev.fedorov.ailife.contracts.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * One single-hop relation row (e.g. "Maria likes loose-leaf earl grey tea").
 * {@code objectId} is nullable: free-text labels with no canonical entity
 * carry only {@code objectLabel}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelationDto(
        UUID id,
        UUID householdId,
        String subjectType,
        UUID subjectId,
        String edge,
        String objectType,
        UUID objectId,
        String objectLabel,
        float confidence,
        String source,
        JsonNode metadata,
        Instant createdAt) {
}
