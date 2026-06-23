package dev.fedorov.ailife.contracts.basket;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.fedorov.ailife.contracts.nutrition.BasketItem;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published on the event bus (topic {@code basket.captured}) when finance recognises a grocery
 * receipt — the async fan-out signal for the nutrition domain (case-1, nutrition.md §inter-agent).
 * Finance parses the receipt once (vision/OCR), writes the expense, and — if it's a grocery basket —
 * publishes this with the extracted line items; {@code nutritionist-agent} consumes it off the bus
 * and runs its basket breakdown, so one receipt reaches both agents with the vision work done once.
 *
 * <p>The items carry only what finance can read off the receipt (name + qty); the nutrition consumer
 * re-estimates the КБЖУ in its own breakdown, so the macro fields of {@link BasketItem} are normally
 * left null here. {@code ownerId} may be null (household-shared). {@code receiptMediaId} links the
 * source photo so the consumer can reference it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BasketCapturedEvent(
        UUID householdId,
        UUID ownerId,
        String merchant,
        List<BasketItem> items,
        UUID receiptMediaId,
        Instant capturedAt) {

    /** Bus topic this event is published under. */
    public static final String TOPIC = "basket.captured";
}
