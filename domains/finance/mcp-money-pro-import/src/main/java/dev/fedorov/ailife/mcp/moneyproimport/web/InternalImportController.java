package dev.fedorov.ailife.mcp.moneyproimport.web;

import dev.fedorov.ailife.contracts.finance.ImportMoneyProCsvInput;
import dev.fedorov.ailife.mcp.moneyproimport.importer.MoneyProImporter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Non-MCP REST passthrough for the importer. finance-agent's CSV-attachment flow already holds the
 * concrete {@link ImportMoneyProCsvInput} (it fetched the bytes from media-service and knows it
 * wants an auto-create import), so a plain HTTP call is the right shape — no LLM-driven MCP tool
 * selection needed. Mirrors mcp-finance's {@code POST /internal/transaction}. The MCP tool stays the
 * entry point for any future LLM-chosen import. Validation failures → 400.
 */
@RestController
@RequestMapping("/internal/import")
public class InternalImportController {

    private final MoneyProImporter importer;

    public InternalImportController(MoneyProImporter importer) {
        this.importer = importer;
    }

    @PostMapping
    public ResponseEntity<?> importCsv(@RequestBody ImportMoneyProCsvInput input) {
        try {
            return ResponseEntity.ok(importer.run(input));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
