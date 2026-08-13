package dev.fedorov.ailife.contracts.travel;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A currency acquired externally for a trip (brought from home) — an <b>inflow</b> of {@code currency}
 * (plans/travel.md §Trip wallet, #437). {@code rateToHome} is the owner-stated ₽ rate at acquisition
 * (e.g. "500 $ по 90" → {@code rateToHome = 90}); null means unrated — EX-b flags that currency
 * "курс не задан" rather than converting it silently. FX is owner-supplied, never fetched.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TripFundingDto(
        UUID id,
        UUID tripId,
        String currency,
        BigDecimal amount,
        BigDecimal rateToHome,
        Instant acquiredAt,
        String note) {
}
