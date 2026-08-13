package dev.fedorov.ailife.contracts.travel;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Log an on-site currency swap (plans/travel.md §Trip wallet, #437): {@code fromAmount} of
 * {@code fromCurrency} exchanged for {@code toAmount} of {@code toCurrency}. All of {@code tripId},
 * {@code fromCurrency}, {@code fromAmount}, {@code toCurrency}, {@code toAmount} are required (amounts
 * {@code >= 0}); the two currencies must differ. No rate field — it is implied by the two amounts.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LogExchangeInput(
        UUID tripId,
        String fromCurrency,
        BigDecimal fromAmount,
        String toCurrency,
        BigDecimal toAmount,
        String note) {
}
