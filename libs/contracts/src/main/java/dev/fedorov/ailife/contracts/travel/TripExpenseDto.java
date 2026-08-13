package dev.fedorov.ailife.contracts.travel;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A spend during a trip — an <b>outflow</b> of {@code currency} (plans/travel.md §Trip wallet, #437).
 * {@code category}/{@code description} are optional context. There is deliberately <b>no</b>
 * {@code paid_by} — settlement is cut, so "who paid" is not tracked (the roster is the only participant
 * context).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TripExpenseDto(
        UUID id,
        UUID tripId,
        String currency,
        BigDecimal amount,
        String category,
        String description,
        Instant spentAt,
        Instant createdAt) {
}
