package dev.fedorov.ailife.contracts.finance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Snapshot of the active budget for the current period window. {@code spent}
 * is the absolute value of net spending in that category over [periodFrom,
 * periodTo) — refunds (positive amounts on an expense category) reduce it.
 * {@code ratio} = spent / limitAmount (null when limitAmount is 0 to avoid
 * divide-by-zero on the wire).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BudgetStatusResult(
        UUID householdId,
        UUID categoryId,
        String categoryName,
        String period,
        BigDecimal limitAmount,
        BigDecimal spent,
        String currency,
        Instant periodFrom,
        Instant periodTo,
        BigDecimal ratio) {
}
