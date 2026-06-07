package dev.fedorov.ailife.contracts.finance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Upsert the active budget for a (household, category, period). If a row with
 * {@code valid_to IS NULL} already exists for the same key, it is closed
 * (valid_to=now) and a new active row is inserted — historical budgets stay
 * queryable.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SetBudgetInput(
        UUID householdId,
        UUID categoryId,
        String period,
        BigDecimal limitAmount,
        String currency) {
}
