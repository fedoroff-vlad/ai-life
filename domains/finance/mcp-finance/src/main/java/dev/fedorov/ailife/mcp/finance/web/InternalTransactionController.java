package dev.fedorov.ailife.mcp.finance.web;

import dev.fedorov.ailife.contracts.finance.AddTransactionInput;
import dev.fedorov.ailife.contracts.finance.FinTransactionDto;
import dev.fedorov.ailife.mcp.finance.domain.FinTransaction;
import dev.fedorov.ailife.mcp.finance.domain.FinTransactionRepository;
import dev.fedorov.ailife.mcp.finance.tools.FinanceMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Non-MCP REST passthrough for transactions. The {@code GET} hydrates a
 * scheduler-driven {@code {transactionId}} payload for finance-agent's
 * {@code transaction.uncategorised} enrichment. The {@code POST} lets an agent
 * write a transaction without going through an LLM-driven MCP tool call — used by
 * the {@code receipt-parser} flow, which has already parsed a concrete
 * {@link AddTransactionInput} from a photo and just needs to persist it. Both
 * mirror {@link InternalBudgetController} / {@link InternalRecurringController}.
 */
@RestController
@RequestMapping("/internal/transaction")
public class InternalTransactionController {

    private final FinTransactionRepository transactions;
    private final FinanceMcpTools tools;

    public InternalTransactionController(FinTransactionRepository transactions, FinanceMcpTools tools) {
        this.transactions = transactions;
        this.tools = tools;
    }

    @GetMapping("/{id}")
    public ResponseEntity<FinTransactionDto> get(@PathVariable UUID id) {
        return transactions.findById(id)
                .map(FinTransaction::toDto)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Delegates straight to {@link FinanceMcpTools#addTransaction} so all the
     * tool's invariants (cross-household guard, currency default, uncategorised
     * one-shot trigger) apply identically. Validation failures → 400.
     */
    @PostMapping
    public ResponseEntity<?> add(@RequestBody AddTransactionInput input) {
        try {
            return ResponseEntity.ok(tools.addTransaction(input));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
