package dev.fedorov.ailife.contracts.nutrition;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Upsert a person's diet profile. Keyed on (householdId, ownerId) — a null ownerId is the
 * household-default. {@code householdId} is required; every other field is applied as given (a full
 * set, not a partial merge — the diet-profiler flow recomputes the whole profile). {@code
 * restrictions} and {@code tastes} are free-form JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SetDietProfileInput(
        UUID householdId,
        UUID ownerId,
        Integer goalKcal,
        BigDecimal goalProteinG,
        BigDecimal goalFatG,
        BigDecimal goalCarbsG,
        JsonNode restrictions,
        JsonNode tastes,
        String notes) {
}
