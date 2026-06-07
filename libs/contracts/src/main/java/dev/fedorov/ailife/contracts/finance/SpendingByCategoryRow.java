package dev.fedorov.ailife.contracts.finance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of the {@code spending_by_category} aggregate. {@code spent} is the
 * absolute value of net spending (refunds reduce it). {@code currency} is the
 * majority currency among the aggregated transactions — multi-currency
 * categories will need post-processing at the agent layer; mcp-finance does
 * not convert.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SpendingByCategoryRow(
        UUID categoryId,
        String categoryName,
        String currency,
        BigDecimal spent,
        long txCount) {
}
