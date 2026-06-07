package dev.fedorov.ailife.contracts.finance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate spending grouped by category over [from, to). {@code kind} filters
 * categories — null defaults to {@code expense} (the common case), an empty
 * string includes every kind, otherwise the value is matched literally
 * (e.g. {@code income}, {@code transfer}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SpendingByCategoryInput(
        UUID householdId,
        Instant from,
        Instant to,
        String kind) {
}
