package dev.fedorov.ailife.mcp.moneyproimport.tools;

import dev.fedorov.ailife.contracts.finance.ImportMoneyProCsvInput;
import dev.fedorov.ailife.contracts.finance.ImportMoneyProCsvResult;
import dev.fedorov.ailife.mcp.moneyproimport.importer.MoneyProImporter;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * MCP surface for the Money Pro CSV importer. Currently a single tool — the
 * module is intentionally narrow, the importer service holds the real logic.
 */
@Component
public class MoneyProImportMcpTools {

    private final MoneyProImporter importer;

    public MoneyProImportMcpTools(MoneyProImporter importer) {
        this.importer = importer;
    }

    @Tool(description = """
            Import a Money Pro CSV export into finance.fin_transaction. The CSV bytes
            are passed base64-encoded so encoding can be autodetected (UTF-8 with
            CP-1251 fallback). Delimiter (comma / semicolon / tab) is autodetected
            too. Required CSV columns: date, account, amount; currency, description
            and id are optional.

            accountMap resolves Money Pro account names (case-insensitive) to existing
            fin_account ids — rows whose account name is not in the map are reported
            as row errors. Every UUID in accountMap is checked against householdId
            before any row is written. categoryMap is optional; unmapped categories
            land as null category_id.

            Idempotency: rows are stored with source='moneypro_import' and an
            external_ref taken from the CSV's id column when present, or a SHA-1
            content hash otherwise. The DB unique on (household, source, external_ref)
            means re-running the same import only inserts what's actually new.

            Set dryRun=true to get the counts that an import would produce without
            writing any rows.
            """)
    public ImportMoneyProCsvResult importMoneyproCsv(ImportMoneyProCsvInput input) {
        return importer.run(input);
    }
}
