package dev.fedorov.ailife.mcp.finance.web;

import dev.fedorov.ailife.contracts.finance.FinTransactionDto;
import dev.fedorov.ailife.contracts.finance.ListTransactionsInput;
import dev.fedorov.ailife.mcp.finance.tools.FinanceMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Non-MCP REST passthrough that lists a household's most recent transactions (newest first), delegating
 * to the {@code list_transactions} tool. Used by finance-agent's {@code transaction-delete} flow (road-test
 * #486, Track H.2 — the finance delete hole) to build the candidate pool it resolves "удали трату про X /
 * последнюю трату" against, without an LLM-driven MCP tool call. Sibling of the singular
 * {@link InternalTransactionController} (get/add/delete by id) and mirrors {@code GET /internal/accounts} /
 * {@code /internal/categories}.
 */
@RestController
@RequestMapping("/internal")
public class InternalTransactionsController {

    /** Cap the candidate pool an agent read pulls — plenty to resolve "the last trata" against. */
    private static final int DEFAULT_LIMIT = 40;

    private final FinanceMcpTools tools;

    public InternalTransactionsController(FinanceMcpTools tools) {
        this.tools = tools;
    }

    @GetMapping("/transactions")
    public ResponseEntity<List<FinTransactionDto>> list(@RequestParam UUID householdId,
                                                        @RequestParam(required = false) Integer limit) {
        int lim = limit == null ? DEFAULT_LIMIT : limit;
        return ResponseEntity.ok(tools.listTransactions(
                new ListTransactionsInput(householdId, null, null, null, null, lim)));
    }
}
