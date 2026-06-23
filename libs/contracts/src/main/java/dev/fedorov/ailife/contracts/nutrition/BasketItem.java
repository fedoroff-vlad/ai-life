package dev.fedorov.ailife.contracts.nutrition;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * One line in an analysed grocery basket: a product name, a free-text quantity ("2 шт", "500 г"),
 * and best-effort per-line macro estimates. Stored inside the {@code basket.items} jsonb array.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BasketItem(
        String name,
        String qty,
        Integer kcal,
        BigDecimal proteinG,
        BigDecimal fatG,
        BigDecimal carbsG) {
}
