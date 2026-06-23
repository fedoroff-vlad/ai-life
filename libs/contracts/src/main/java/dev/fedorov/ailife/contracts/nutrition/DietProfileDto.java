package dev.fedorov.ailife.contracts.nutrition;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * A person's diet profile — one per (household, owner). Mirrors a {@code nutrition.diet_profile}
 * row; a null {@code ownerId} is the household-default. Holds the wife / infant profiles too.
 * {@code restrictions} (allergies / halal / vegan / infant-stage / …) and {@code tastes} are
 * free-form JSON. Daily macro goals are optional.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DietProfileDto(
        UUID id,
        UUID householdId,
        UUID ownerId,
        Integer goalKcal,
        java.math.BigDecimal goalProteinG,
        java.math.BigDecimal goalFatG,
        java.math.BigDecimal goalCarbsG,
        JsonNode restrictions,
        JsonNode tastes,
        String notes,
        Instant updatedAt) {
}
