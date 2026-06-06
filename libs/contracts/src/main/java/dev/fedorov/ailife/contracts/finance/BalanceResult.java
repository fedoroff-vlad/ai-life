package dev.fedorov.ailife.contracts.finance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Account balance = opening_balance + sum(transactions.amount) as of the query time.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BalanceResult(
        UUID accountId,
        String currency,
        BigDecimal balance) {
}
