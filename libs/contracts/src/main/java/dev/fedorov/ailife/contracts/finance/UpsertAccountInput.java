package dev.fedorov.ailife.contracts.finance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpsertAccountInput(
        UUID id,
        UUID householdId,
        UUID ownerId,
        String name,
        String type,
        String currency,
        BigDecimal openingBalance,
        Boolean archived) {
}
