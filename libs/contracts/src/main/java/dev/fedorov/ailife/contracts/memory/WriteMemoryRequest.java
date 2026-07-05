package dev.fedorov.ailife.contracts.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WriteMemoryRequest(
        UUID householdId,
        UUID userId,
        UUID personId,
        String source,
        String text,
        JsonNode metadata) {
}
