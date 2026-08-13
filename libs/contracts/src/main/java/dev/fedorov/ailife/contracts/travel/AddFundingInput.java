package dev.fedorov.ailife.contracts.travel;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Record a currency acquired for a trip (plans/travel.md §Trip wallet, #437). {@code tripId},
 * {@code currency} and {@code amount} are required ({@code amount >= 0}). {@code rateToHome} is the
 * optional owner-stated ₽ rate at acquisition; null leaves the currency unrated (EX-b flags it in the
 * ₽ tally). Use {@code logExchange} instead when the currency was bought on-site with another held
 * currency, so the source balance is debited too.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AddFundingInput(
        UUID tripId,
        String currency,
        BigDecimal amount,
        BigDecimal rateToHome,
        String note) {
}
