package dev.fedorov.ailife.contracts.finance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpsertCategoryInput(
        UUID id,
        UUID householdId,
        UUID parentId,
        String name,
        String kind,
        String icon) {
}
