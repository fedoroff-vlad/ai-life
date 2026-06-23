package dev.fedorov.ailife.contracts.nutrition;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Save an analysed grocery basket. Only {@code householdId} is required; {@code capturedAt} defaults
 * to now and {@code source} to {@code manual} when omitted. {@code items} are the parsed line items,
 * {@code analysis} the optional breakdown. Used directly (a basket sent to the nutritionist) and by
 * the bus fan-out (a grocery receipt → basket.captured → stored basket).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SaveBasketInput(
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
        JsonNode analysis) {
}
