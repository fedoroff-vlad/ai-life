package dev.fedorov.ailife.contracts.travel;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Create a family trip (plans/travel.md §Trip wallet, #437). {@code householdId} and {@code title} are
 * required; {@code ownerId} is the creating user. {@code destination}/{@code startDate}/{@code endDate}
 * are optional. {@code homeCurrency} defaults to {@code RUB} when null — the currency the ₽ tally
 * converts into. The trip starts in {@code planning}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateTripInput(
        UUID householdId,
        UUID ownerId,
        String title,
        String destination,
        LocalDate startDate,
        LocalDate endDate,
        String homeCurrency) {
}
