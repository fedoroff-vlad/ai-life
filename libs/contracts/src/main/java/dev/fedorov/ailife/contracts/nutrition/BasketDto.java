package dev.fedorov.ailife.contracts.nutrition;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One analysed grocery basket. Mirrors a {@code nutrition.basket} row. {@code source} is
 * receipt|manual; {@code receiptMediaId} links the source receipt photo. {@code items} are the
 * parsed line items (stored as jsonb); the macro fields are basket totals. {@code analysis} is the
 * free-form breakdown (good/watch/cut vs the diet profile) the basket-analyst flow fills (NU-f).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BasketDto(
        UUID id,
        UUID householdId,
        UUID ownerId,
        Instant capturedAt,
        String merchant,
        String source,
        UUID receiptMediaId,
        List<BasketItem> items,
        Integer kcal,
        BigDecimal proteinG,
        BigDecimal fatG,
        BigDecimal carbsG,
        JsonNode analysis,
        Instant createdAt) {
}
