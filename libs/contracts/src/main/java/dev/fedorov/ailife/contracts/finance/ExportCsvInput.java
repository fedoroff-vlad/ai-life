package dev.fedorov.ailife.contracts.finance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Filter for export_csv. {@code householdId} is required; {@code accountId} /
 * {@code categoryId} / {@code from} (inclusive) / {@code to} (exclusive) are
 * optional and narrow the export. Same filter semantics as
 * {@link ListTransactionsInput}, but the export is bounded by a higher hard cap
 * (not the 200-row list cap) and ordered oldest-first for a ledger-style file.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExportCsvInput(
        UUID householdId,
        UUID accountId,
        UUID categoryId,
        Instant from,
        Instant to) {
}
