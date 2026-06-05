package dev.fedorov.ailife.contracts.memory;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One recall hit: the memory plus the cosine distance (0 = identical, 2 = opposite).
 * Callers usually want score-as-similarity = {@code 1 - distance}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecallMemoryHit(
        MemoryDto memory,
        double distance) {
}
