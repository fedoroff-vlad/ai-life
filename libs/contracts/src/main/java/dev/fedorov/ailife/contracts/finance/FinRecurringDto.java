package dev.fedorov.ailife.contracts.finance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FinRecurringDto(
        UUID id,
        UUID householdId,
        UUID ownerId,
        UUID accountId,
        UUID categoryId,
        String name,
        BigDecimal amount,
        String currency,
        String cron,
        Instant nextDue,
        String note,
        boolean autoRemind,
        UUID scheduleId,
        Instant createdAt) {
}
