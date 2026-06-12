package dev.fedorov.ailife.contracts.tasks;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpsertProjectInput(
        UUID id,
        UUID householdId,
        UUID ownerId,
        String name,
        String status,
        String note) {
}
