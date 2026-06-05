package dev.fedorov.ailife.contracts.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WriteRelationRequest(
        UUID householdId,
        String subjectType,
        UUID subjectId,
        String edge,
        String objectType,
        UUID objectId,
        String objectLabel,
        Float confidence,
        String source,
        JsonNode metadata) {
}
