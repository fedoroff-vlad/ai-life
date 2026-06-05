package dev.fedorov.ailife.contracts.memory;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Top-k recall by cosine similarity. Scope filter: householdId is required,
 * userId/personId narrow further when set. {@code k} defaults to 5 server-side
 * if null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecallMemoryRequest(
        UUID householdId,
        UUID userId,
        UUID personId,
        String query,
        Integer k) {
}
