package dev.fedorov.ailife.contracts.finance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * The household's gift-spending envelope for the budget-aware gift-recommender
 * flow (Stage 4 / Track D). MVP: the active monthly budget on the household's
 * "Gifts" expense category. {@code amount} is the budget limit, {@code currency}
 * its currency, {@code remaining} = limit − spent in the current month window
 * (may be negative when over budget). Composed from the existing budget-status
 * read — no new persistence.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GiftBudgetResult(
        BigDecimal amount,
        String currency,
        BigDecimal remaining) {
}
