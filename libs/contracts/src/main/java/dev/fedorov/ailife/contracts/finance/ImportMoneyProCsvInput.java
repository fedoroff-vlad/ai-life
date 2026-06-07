package dev.fedorov.ailife.contracts.finance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;
import java.util.UUID;

/**
 * Input for the Money Pro CSV import tool.
 *
 * <p>The CSV bytes are passed base64-encoded so the MCP boundary stays text-only
 * regardless of the source file's encoding. Encoding is autodetected (UTF-8 with
 * CP-1251 fallback for Russian Money Pro exports); pass {@code encoding} to override.
 *
 * <p>{@code accountMap} resolves Money Pro account names (as they appear in the
 * "Account" column, case-insensitive) to existing {@code fin_account.id} UUIDs. Rows
 * whose account name does not match anything in the map are reported as row errors.
 * Cross-household guard: every UUID in the map must belong to {@code householdId} —
 * the import refuses to start otherwise.
 *
 * <p>{@code categoryMap} is optional; unmapped categories simply land as null
 * {@code category_id} and can be back-filled later by a categorizer skill.
 *
 * <p>{@code fileRef} is a free-form label for traceability (filename / source URL),
 * surfaced in logs only.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportMoneyProCsvInput(
        UUID householdId,
        String csvBase64,
        String encoding,
        Map<String, UUID> accountMap,
        Map<String, UUID> categoryMap,
        String fileRef,
        Boolean dryRun) {
}
