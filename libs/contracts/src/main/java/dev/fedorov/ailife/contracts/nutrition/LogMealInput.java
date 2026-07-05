package dev.fedorov.ailife.contracts.nutrition;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Log a meal. Only {@code householdId} + {@code description} are required; {@code eatenAt} defaults
 * to now and {@code source} to {@code text} when omitted. {@code ownerId} attributes the meal to a
 * person (null = household-shared). {@code items} is the free-form parsed breakdown and the macros
 * are best-effort estimates the food-log flow fills. {@code imageMediaId} links a meal photo.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LogMealInput(
        UUID householdId,
        UUID ownerId,
        Instant eatenAt,
        String source,
        String description,
        JsonNode items,
        Integer kcal,
        BigDecimal proteinG,
        BigDecimal fatG,
        BigDecimal carbsG,
        UUID imageMediaId) {
}
