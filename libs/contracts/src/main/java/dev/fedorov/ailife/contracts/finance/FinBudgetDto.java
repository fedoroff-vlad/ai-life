package dev.fedorov.ailife.contracts.finance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FinBudgetDto(
        UUID id,
        UUID householdId,
        UUID categoryId,
        String period,
        BigDecimal limitAmount,
        String currency,
        Instant validFrom,
        Instant validTo,
        UUID scheduleId) {
}
