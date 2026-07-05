package dev.fedorov.ailife.contracts.nutrition;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One logged meal. Mirrors a {@code nutrition.meal_log} row. {@code source} is photo|text;
 * {@code items} is the free-form parsed food breakdown (jsonb). Macros are best-effort estimates
 * (LLM now; mcp-food-data later). {@code imageMediaId} points at the media-service meal photo.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MealLogDto(
        UUID id,
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
        UUID imageMediaId,
        Instant createdAt) {
}
