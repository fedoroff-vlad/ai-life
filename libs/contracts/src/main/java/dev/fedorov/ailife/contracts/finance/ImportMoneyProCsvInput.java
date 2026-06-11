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
 * whose account name does not match anything in the map are reported as row errors —
 * unless {@code autoCreateAccounts} is set. Cross-household guard: every UUID in the
 * map must belong to {@code householdId} — the import refuses to start otherwise.
 *
 * <p>{@code autoCreateAccounts}: when true, a Money Pro account name not found in
 * {@code accountMap} (and not matching an existing account by name) auto-creates a
 * {@code fin_account} in the household instead of erroring. Best for a one-time history
 * import — the user can rename / merge afterwards. {@code accountMap} may be empty in
 * this mode. The created account names are returned in the result's {@code createdAccounts}.
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
        Boolean dryRun,
        Boolean autoCreateAccounts) {

    /** Back-compat constructor for callers that predate auto-create (defaults it off). */
    public ImportMoneyProCsvInput(UUID householdId, String csvBase64, String encoding,
                                  Map<String, UUID> accountMap, Map<String, UUID> categoryMap,
                                  String fileRef, Boolean dryRun) {
        this(householdId, csvBase64, encoding, accountMap, categoryMap, fileRef, dryRun, null);
    }
}
