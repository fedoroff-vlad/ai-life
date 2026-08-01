package dev.fedorov.ailife.mcp.finance.web;

import dev.fedorov.ailife.contracts.finance.FinAccountDto;
import dev.fedorov.ailife.contracts.finance.UpsertAccountInput;
import dev.fedorov.ailife.mcp.finance.tools.FinanceMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Non-MCP REST passthrough for accounts. The {@code GET /internal/accounts} lists a household's accounts
 * (ordered by name), delegating to the {@code list_accounts} tool; the {@code POST /internal/account}
 * creates/updates one, delegating to {@code upsert_account}. Used by finance-agent's flows to read/write
 * accounts without an LLM-driven MCP tool call: {@code receipt-parser} resolves a target account via the
 * GET, and {@code account-manager} (ADR-0002 slice 4b) creates one via the POST — with the household the
 * shared {@code SharingResolver} already routed to (personal vs shared). Mirrors {@link
 * InternalCategoryController} / {@link InternalTransactionController}.
 */
@RestController
@RequestMapping("/internal")
public class InternalAccountController {

    private final FinanceMcpTools tools;

    public InternalAccountController(FinanceMcpTools tools) {
        this.tools = tools;
    }

    @GetMapping("/accounts")
    public ResponseEntity<List<FinAccountDto>> list(@RequestParam UUID householdId) {
        return ResponseEntity.ok(tools.listAccounts(householdId));
    }

    /**
     * Delegates straight to {@link FinanceMcpTools#upsertAccount} so the tool's invariants (required
     * fields, type/currency validation) apply identically. Validation failures → 400.
     */
    @PostMapping("/account")
    public ResponseEntity<?> upsert(@RequestBody UpsertAccountInput input) {
        try {
            return ResponseEntity.ok(tools.upsertAccount(input));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
