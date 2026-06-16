package dev.fedorov.ailife.mcp.finance.web;

import dev.fedorov.ailife.contracts.finance.FinRecurringDto;
import dev.fedorov.ailife.mcp.finance.domain.FinRecurring;
import dev.fedorov.ailife.mcp.finance.domain.FinRecurringRepository;
import dev.fedorov.ailife.mcp.finance.tools.FinanceMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Non-MCP REST passthrough so system callers (finance-agent's recurring.due
 * trigger enrichment, driven by scheduler-service) can read a single
 * recurring row by plain HTTP without the MCP / LLM tax. Mirrors
 * {@link InternalBudgetController} (PR27a).
 *
 * <p>404 is the "row was deleted upstream" signal — finance-agent treats it
 * the same way budget enrichment treats a deleted budget: stamp the payload
 * and let the skill emit SKIP.
 */
@RestController
@RequestMapping("/internal/recurring")
public class InternalRecurringController {

    private final FinRecurringRepository recurring;

    public InternalRecurringController(FinRecurringRepository recurring) {
        this.recurring = recurring;
    }

    @GetMapping("/{id}")
    public ResponseEntity<FinRecurringDto> get(@PathVariable UUID id) {
        return recurring.findById(id)
                .map(FinRecurring::toDto)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Recompute {@code next_due} from the row's cron + current time and save
     * it. Called by finance-agent after a successful {@code recurring.due}
     * trigger so the column stops being a stale snapshot once the cron starts
     * firing (PR29b's known limitation). 404 when the row was deleted upstream
     * — the caller treats that as a no-op.
     *
     * <p>scheduler-service still advances its own {@code next_run_ts}
     * independently; this endpoint only updates the agent-visible column on
     * {@code fin_recurring} (used by {@code list_recurring}).
     */
    @PostMapping("/{id}/advance")
    @Transactional
    public ResponseEntity<FinRecurringDto> advance(@PathVariable UUID id) {
        return recurring.findById(id)
                .map(row -> {
                    row.setNextDue(FinanceMcpTools.nextDueFromCron(row.getCron(), Instant.now()));
                    FinRecurring saved = recurring.save(row);
                    return ResponseEntity.ok(saved.toDto());
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
