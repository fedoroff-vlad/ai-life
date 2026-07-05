package dev.fedorov.ailife.memory.domain;

import tools.jackson.databind.JsonNode;
import dev.fedorov.ailife.contracts.memory.MemoryDto;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for one row of memory.memories. We keep the embedding off the
 * row — recall never returns it (too big, no client uses it), and writes go
 * through a dedicated path that takes the float[] separately.
 */
public record MemoryRow(
        UUID id,
        UUID householdId,
        UUID userId,
        UUID personId,
        String source,
        String text,
        JsonNode metadata,
        Instant createdAt) {

    public MemoryDto toDto() {
        return new MemoryDto(id, householdId, userId, personId, source, text, metadata, createdAt);
    }
}
