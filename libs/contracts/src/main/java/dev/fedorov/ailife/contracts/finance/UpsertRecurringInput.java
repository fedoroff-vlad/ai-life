package dev.fedorov.ailife.contracts.finance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Upsert a recurring payment template. {@code cron} is a Spring
 * {@code CronExpression} (UTC) — the tool layer validates it and recomputes
 * {@code next_due} on every upsert so a changed cadence takes effect
 * immediately. If {@code currency} is omitted the account's currency is used.
 * {@code autoRemind} stays a column-level flag in this PR; the
 * scheduler-side auto-registration of a reminder cron lands in a follow-up
 * (same pattern PR27b established for budgets).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpsertRecurringInput(
        UUID id,
        UUID householdId,
        UUID ownerId,
        UUID accountId,
        UUID categoryId,
        String name,
        BigDecimal amount,
        String currency,
        String cron,
        String note,
        Boolean autoRemind) {
}
