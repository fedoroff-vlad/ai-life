package dev.fedorov.ailife.contracts.wardrobe;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The owner's personal style profile — one per person (household, owner). Mirrors a
 * {@code wardrobe.style_profile} row. The vision "analyse me" flow fills {@code personType},
 * {@code bodyShape}, {@code colourType} (цветотип) and {@code suitableFabrics}; the owner types
 * the body measurements. {@code suitableFabrics} and {@code measurements} are free-form JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StyleProfileDto(
        UUID id,
        UUID householdId,
        UUID ownerId,
        String personType,
        String bodyShape,
        String colourType,
        JsonNode suitableFabrics,
        Integer heightCm,
        BigDecimal weightKg,
        JsonNode measurements,
        String notes,
        UUID imageMediaId,
        Instant updatedAt) {
}
