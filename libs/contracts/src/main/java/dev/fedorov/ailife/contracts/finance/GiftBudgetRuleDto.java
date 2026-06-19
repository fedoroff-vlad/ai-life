package dev.fedorov.ailife.contracts.finance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A relationship-tiered gift-budget rule (Stage 4 / Track D3): the household's
 * budget for a gift to someone of {@code relationship} tier. Editable data set
 * via {@code set_gift_budget_rule} and read via {@code list_gift_budget_rules}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GiftBudgetRuleDto(
        UUID id,
        UUID householdId,
        String relationship,
        BigDecimal amount,
        String currency,
        Instant createdAt,
        Instant updatedAt) {
}
