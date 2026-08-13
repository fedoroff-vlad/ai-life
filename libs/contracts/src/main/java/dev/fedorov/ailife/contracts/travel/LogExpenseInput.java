package dev.fedorov.ailife.contracts.travel;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Log a spend on a trip (plans/travel.md §Trip wallet, #437). {@code tripId}, {@code currency} and
 * {@code amount} are required ({@code amount >= 0}). {@code category}/{@code description} are optional.
 * No {@code paid_by} — settlement is cut (§Trip wallet).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LogExpenseInput(
        UUID tripId,
        String currency,
        BigDecimal amount,
        String category,
        String description) {
}
