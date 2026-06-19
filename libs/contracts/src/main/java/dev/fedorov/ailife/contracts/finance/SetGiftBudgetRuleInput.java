package dev.fedorov.ailife.contracts.finance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Upsert a relationship-tiered gift-budget rule (Stage 4 / Track D3): how much
 * the household budgets for a gift to someone of a given {@code relationship}
 * tier (e.g. "parent" → 20000 RUB). {@code relationship} is the free-text tier
 * label matching {@code core.people.relationship}; the rule is keyed by
 * (household, relationship) case-insensitively. All fields required.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SetGiftBudgetRuleInput(
        UUID householdId,
        String relationship,
        BigDecimal amount,
        String currency) {
}
