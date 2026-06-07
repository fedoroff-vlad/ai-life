package dev.fedorov.ailife.contracts.finance;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One row-level error from a CSV import. {@code row} is 1-based and refers to
 * the CSV's logical row number after the header (so the first data row is row 1).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportRowError(int row, String message) {
}
