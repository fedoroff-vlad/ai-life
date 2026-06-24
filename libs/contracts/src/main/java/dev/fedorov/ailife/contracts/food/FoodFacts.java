package dev.fedorov.ailife.contracts.food;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * Nutrition facts for one food product, returned by the {@code mcp-food-data} capability's
 * {@code food_lookup} tool. Sourced from <b>Open Food Facts</b> by barcode or product-name search.
 * Macros are <b>per 100 g/ml</b> as the database stores them ({@code kcal100g} / {@code protein100g}
 * / {@code fat100g} / {@code carbs100g}); {@code nutriScore} is the a–e grade when present.
 *
 * <p>{@code name} (and the macro fields) are null when the source has no product for the query —
 * a {@link FoodFacts} with a null {@code name} means "no data", not an error (mirrors
 * {@code market.Quote}'s null {@code price}). The capability returns data only; reasoning over it
 * (КБЖУ for a basket, deficits vs a diet profile) is the calling agent's skill. Absent fields stay
 * null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FoodFacts(
        String query,
        String name,
        String brand,
        String barcode,
        String quantity,
        String nutriScore,
        Integer kcal100g,
        BigDecimal protein100g,
        BigDecimal fat100g,
        BigDecimal carbs100g) {
}
