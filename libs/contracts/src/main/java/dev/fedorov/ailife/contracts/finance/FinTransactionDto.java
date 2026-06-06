package dev.fedorov.ailife.contracts.finance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FinTransactionDto(
        UUID id,
        UUID householdId,
        UUID accountId,
        UUID categoryId,
        UUID ownerId,
        BigDecimal amount,
        String currency,
        Instant ts,
        String note,
        String source,
        String externalRef,
        Instant createdAt) {
}
