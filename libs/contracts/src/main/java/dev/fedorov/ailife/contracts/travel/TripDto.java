package dev.fedorov.ailife.contracts.travel;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A persisted family trip — the header of the multi-currency trip wallet (plans/travel.md §Trip wallet,
 * #437). Keyed by {@code id}; scoped to a {@code householdId} (the visibility boundary) and created by
 * an {@code ownerId} (a {@code core.users} row). {@code homeCurrency} (default {@code RUB}) is the
 * currency the ₽ tally converts into. {@code status} is {@code planning|active|closed}. The fundings,
 * exchanges and expenses that make up the wallet hang off this trip; balance math lands in EX-b.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TripDto(
        UUID id,
        UUID householdId,
        UUID ownerId,
        String title,
        String destination,
        LocalDate startDate,
        LocalDate endDate,
        String homeCurrency,
        String status,
        Instant createdAt,
        Instant updatedAt) {
}
