package dev.fedorov.ailife.mcp.moneyproimport.importer;

import dev.fedorov.ailife.contracts.finance.ImportMoneyProCsvInput;
import dev.fedorov.ailife.contracts.finance.ImportMoneyProCsvResult;
import dev.fedorov.ailife.contracts.finance.ImportRowError;
import dev.fedorov.ailife.mcp.moneyproimport.domain.FinAccount;
import dev.fedorov.ailife.mcp.moneyproimport.domain.FinAccountRepository;
import dev.fedorov.ailife.mcp.moneyproimport.domain.FinTransaction;
import dev.fedorov.ailife.mcp.moneyproimport.domain.FinTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The Money Pro CSV importer.
 *
 * <p>Idempotency: rows are written with {@code source='moneypro_import'} and an
 * {@code external_ref} taken from the CSV's {@code ID} column when present, or
 * a SHA-1 of {@code date|account|amount|note} otherwise. The DB unique on
 * {@code (household_id, source, external_ref)} is the actual guard — we also
 * check existence client-side so re-imports report sensible "skipped" counts.
 *
 * <p>Cross-household guard: every UUID in {@code accountMap} is verified to
 * belong to the supplied {@code householdId} <em>before</em> any row is inserted.
 * Errors at this stage are fatal; per-row errors during the loop are collected
 * into the result and don't abort the import.
 */
@Service
public class MoneyProImporter {

    private static final Logger log = LoggerFactory.getLogger(MoneyProImporter.class);

    static final String SOURCE = "moneypro_import";
    private static final int MAX_ERRORS_RETURNED = 20;

    private final FinAccountRepository accounts;
    private final FinTransactionRepository transactions;

    public MoneyProImporter(FinAccountRepository accounts, FinTransactionRepository transactions) {
        this.accounts = accounts;
        this.transactions = transactions;
    }

    @Transactional
    public ImportMoneyProCsvResult run(ImportMoneyProCsvInput input) {
        requireField(input.householdId(), "householdId");
        requireField(input.csvBase64(), "csvBase64");
        Map<String, UUID> accountMap = lowerKeyed(input.accountMap());
        Map<String, UUID> categoryMap = lowerKeyed(input.categoryMap());
        boolean dryRun = Boolean.TRUE.equals(input.dryRun());

        verifyAccountMapScope(input.householdId(), accountMap);

        byte[] bytes = Base64.getDecoder().decode(input.csvBase64());
        String text = CsvSniffer.decode(bytes, input.encoding());
        List<String> lines = CsvReader.splitLines(text);
        if (lines.isEmpty()) {
            return new ImportMoneyProCsvResult(dryRun, 0, 0, 0, 0, List.of());
        }
        char delimiter = CsvSniffer.detectDelimiter(lines.get(0));
        List<String> headers = CsvReader.splitLine(lines.get(0), delimiter);
        HeaderIndex idx = HeaderIndex.from(headers);

        Map<UUID, FinAccount> accountCache = new HashMap<>();
        List<ImportRowError> errors = new ArrayList<>();
        int total = 0, created = 0, skipped = 0, errored = 0;

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            total++;
            int rowNumber = total;
            try {
                List<String> cells = CsvReader.splitLine(line, delimiter);
                String accountName = cell(cells, idx.account).trim();
                UUID accountId = accountMap.get(accountName.toLowerCase(Locale.ROOT));
                if (accountId == null) {
                    throw new IllegalArgumentException("Unknown account: '" + accountName + "'");
                }
                FinAccount account = accountCache.computeIfAbsent(accountId, this::loadAccount);

                BigDecimal amount = AmountParser.parse(cell(cells, idx.amount));
                Instant ts = DateParser.parse(cell(cells, idx.date));
                String currency = idx.currency >= 0 ? cell(cells, idx.currency).trim() : "";
                if (currency.isEmpty()) currency = account.getCurrency();
                String note = idx.note >= 0 ? cell(cells, idx.note).trim() : null;
                if (note != null && note.isEmpty()) note = null;
                String categoryName = idx.category >= 0 ? cell(cells, idx.category).trim() : "";
                UUID categoryId = categoryName.isEmpty() ? null
                        : categoryMap.get(categoryName.toLowerCase(Locale.ROOT));

                String externalRef = idx.id >= 0 ? cell(cells, idx.id).trim() : "";
                if (externalRef.isEmpty()) {
                    externalRef = stableHash(cell(cells, idx.date), accountName,
                            cell(cells, idx.amount), note == null ? "" : note);
                }

                if (transactions.existsByHouseholdIdAndSourceAndExternalRef(
                        input.householdId(), SOURCE, externalRef)) {
                    skipped++;
                    continue;
                }
                if (!dryRun) {
                    transactions.save(new FinTransaction(
                            UUID.randomUUID(),
                            input.householdId(),
                            accountId,
                            categoryId,
                            null,
                            amount,
                            currency,
                            ts,
                            note,
                            SOURCE,
                            externalRef));
                }
                created++;
            } catch (RuntimeException e) {
                errored++;
                if (errors.size() < MAX_ERRORS_RETURNED) {
                    errors.add(new ImportRowError(rowNumber, e.getMessage()));
                }
                log.debug("Money Pro CSV row {} failed: {}", rowNumber, e.getMessage());
            }
        }

        return new ImportMoneyProCsvResult(dryRun, total, created, skipped, errored, errors);
    }

    private FinAccount loadAccount(UUID id) {
        return accounts.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Account not found: " + id));
    }

    private void verifyAccountMapScope(UUID householdId, Map<String, UUID> accountMap) {
        if (accountMap.isEmpty()) {
            throw new IllegalArgumentException("accountMap is empty — Money Pro account names must be mapped to fin_account ids");
        }
        for (Map.Entry<String, UUID> e : accountMap.entrySet()) {
            FinAccount a = accounts.findById(e.getValue()).orElseThrow(
                    () -> new IllegalArgumentException("Account not found in accountMap: " + e.getValue()));
            if (!a.getHouseholdId().equals(householdId)) {
                throw new IllegalArgumentException(
                        "accountMap entry '" + e.getKey() + "' points to an account in a different household");
            }
        }
    }

    private static Map<String, UUID> lowerKeyed(Map<String, UUID> in) {
        if (in == null || in.isEmpty()) return Map.of();
        Map<String, UUID> out = new HashMap<>(in.size());
        for (Map.Entry<String, UUID> e : in.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                out.put(e.getKey().trim().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
        return out;
    }

    private static String cell(List<String> cells, int idx) {
        if (idx < 0 || idx >= cells.size()) return "";
        return cells.get(idx);
    }

    private static String stableHash(String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            for (String p : parts) {
                md.update((p == null ? "" : p).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            byte[] digest = md.digest();
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    private static void requireField(Object value, String name) {
        if (value == null) throw new IllegalArgumentException("Missing required field: " + name);
        if (value instanceof String s && s.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + name);
        }
    }
}
