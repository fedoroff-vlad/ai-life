package dev.fedorov.ailife.contracts.finance;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result of export_csv. {@code csv} is the full CSV document (RFC-4180-style:
 * comma-separated, header row, fields with comma/quote/newline quoted);
 * {@code rowCount} is the number of data rows (excluding the header);
 * {@code truncated} is true when the export hit the hard cap and more rows
 * matched the filter than were returned.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CsvExportResult(
        String csv,
        int rowCount,
        boolean truncated) {
}
