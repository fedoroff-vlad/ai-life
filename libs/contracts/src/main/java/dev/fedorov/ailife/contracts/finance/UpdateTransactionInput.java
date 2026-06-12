package dev.fedorov.ailife.contracts.finance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Partial update of an existing transaction. {@code id} is required; every other
 * field is optional and applied only when non-null — a null means "leave
 * unchanged", so this tool cannot clear an already-set field (e.g. it can move a
 * transaction to a different category but not un-categorise it). Provenance
 * (householdId, source, externalRef) and createdAt are immutable.
 *
 * <p>Sign convention follows {@link AddTransactionInput}: expense&lt;0, income&gt;0.
 * Moving the row to another account ({@code accountId}) is allowed only within the
 * same household.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpdateTransactionInput(
        UUID id,
        UUID accountId,
        UUID categoryId,
        UUID ownerId,
        BigDecimal amount,
        String currency,
        Instant ts,
        String note) {
}
