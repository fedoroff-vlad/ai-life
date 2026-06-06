package dev.fedorov.ailife.contracts.finance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListTransactionsInput(
        UUID householdId,
        UUID accountId,
        UUID categoryId,
        Instant from,
        Instant to,
        Integer limit) {
}
