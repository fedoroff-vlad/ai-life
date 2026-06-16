package dev.fedorov.ailife.mcp.finance.web;

import dev.fedorov.ailife.contracts.finance.FinAccountDto;
import dev.fedorov.ailife.mcp.finance.tools.FinanceMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Non-MCP REST passthrough listing a household's accounts (ordered by name),
 * delegating to the {@code list_accounts} tool. Used by finance-agent's
 * {@code receipt-parser} flow to resolve a target account for a parsed
 * transaction without an LLM-driven MCP tool call. Mirrors
 * {@link InternalBudgetController} / {@link InternalTransactionController}.
 */
@RestController
@RequestMapping("/internal/accounts")
public class InternalAccountController {

    private final FinanceMcpTools tools;

    public InternalAccountController(FinanceMcpTools tools) {
        this.tools = tools;
    }

    @GetMapping
    public ResponseEntity<List<FinAccountDto>> list(@RequestParam UUID householdId) {
        return ResponseEntity.ok(tools.listAccounts(householdId));
    }
}
