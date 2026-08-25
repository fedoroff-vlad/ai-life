package dev.fedorov.ailife.mcp.finance.web;

import dev.fedorov.ailife.contracts.finance.AddTransactionInput;
import dev.fedorov.ailife.contracts.finance.FinTransactionDto;
import dev.fedorov.ailife.contracts.finance.UpdateTransactionInput;
import dev.fedorov.ailife.mcp.finance.domain.FinTransaction;
import dev.fedorov.ailife.mcp.finance.domain.FinTransactionRepository;
import dev.fedorov.ailife.mcp.finance.tools.FinanceMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
 * {@link AddTransactionInput} from a photo and just needs to persist it. The
 * {@code DELETE} returns the deleted row — the deterministic reversal behind the
 * "отмени последнее" undo primitive (road-test #486, Track H): finance-agent's
 * {@code /actions/undo} calls it to reverse a just-written transaction. The
 * {@code PUT} is the deterministic partial edit behind finance-agent's user-facing
 * {@code transaction-edit} chat flow (#486/Track H.2). Both write paths mirror
 * {@link InternalBudgetController} / {@link InternalRecurringController}.
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

    /**
     * Partial edit of a transaction (the {@code transaction-edit} chat flow, #486/Track H.2): patches
     * only the supplied fields (see {@link FinanceMcpTools#updateTransaction} — non-null only), so an
     * amount/note fix sends just those. The path id is authoritative (overrides any id in the body).
     * {@code 200} with the updated row; {@code 404} when the id is unknown.
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable UUID id, @RequestBody UpdateTransactionInput input) {
        UpdateTransactionInput withId = new UpdateTransactionInput(id, input.accountId(), input.categoryId(),
                input.ownerId(), input.amount(), input.currency(), input.ts(), input.note());
        try {
            return ResponseEntity.ok(tools.updateTransaction(withId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Deletes a transaction and returns the deleted row, delegating to
     * {@link FinanceMcpTools#deleteTransaction}. The undo reversal (#486/H): finance-agent's
     * {@code /actions/undo} calls it to reverse a just-written transaction. Unknown id → 404.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(tools.deleteTransaction(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }
}
