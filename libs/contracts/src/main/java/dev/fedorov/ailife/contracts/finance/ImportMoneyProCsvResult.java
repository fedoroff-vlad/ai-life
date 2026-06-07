package dev.fedorov.ailife.contracts.finance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Result of a Money Pro CSV import attempt. Counters refer to logical rows after
 * the header; {@code errors} is capped at the first few problematic rows to keep
 * the LLM-facing response bounded.
 *
 * <p>When {@code dryRun} is true nothing is written — {@code created} and
 * {@code skipped} reflect what would have happened against the current DB state.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportMoneyProCsvResult(
        boolean dryRun,
        int totalRows,
        int created,
        int skipped,
        int errored,
        List<ImportRowError> errors) {
}
