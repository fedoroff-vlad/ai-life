package dev.fedorov.ailife.mcp.finance.web;

import dev.fedorov.ailife.contracts.finance.FinTransactionDto;
import dev.fedorov.ailife.mcp.finance.domain.FinTransaction;
import dev.fedorov.ailife.mcp.finance.domain.FinTransactionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Non-MCP REST passthrough so finance-agent's {@code transaction.uncategorised}
 * enrichment can hydrate a scheduler-driven {@code {transactionId}} payload
 * into the full transaction. Mirrors {@link InternalBudgetController} and
 * {@link InternalRecurringController}.
 */
@RestController
@RequestMapping("/internal/transaction")
public class InternalTransactionController {

    private final FinTransactionRepository transactions;

    public InternalTransactionController(FinTransactionRepository transactions) {
        this.transactions = transactions;
    }

    @GetMapping("/{id}")
    public ResponseEntity<FinTransactionDto> get(@PathVariable UUID id) {
        return transactions.findById(id)
                .map(FinTransaction::toDto)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
