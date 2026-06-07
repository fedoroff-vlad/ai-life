package dev.fedorov.ailife.mcp.finance.web;

import dev.fedorov.ailife.contracts.finance.BudgetStatusResult;
import dev.fedorov.ailife.mcp.finance.tools.FinanceMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Non-MCP REST passthrough so system callers (finance-agent's budget.alert
 * trigger enrichment, driven by scheduler-service via orchestrator) can read
 * budget status by plain HTTP without the MCP / LLM tax. Mirrors mcp-ics-import's
 * {@code /internal/pull/{subscriptionId}} pattern.
 *
 * The MCP tool {@code get_budget_status} stays the right entry point for
 * user-initiated questions.
 */
@RestController
@RequestMapping("/internal/budget-status")
public class InternalBudgetController {

    private final FinanceMcpTools tools;

    public InternalBudgetController(FinanceMcpTools tools) {
        this.tools = tools;
    }

    @GetMapping
    public ResponseEntity<BudgetStatusResult> status(@RequestParam UUID householdId,
                                                     @RequestParam UUID categoryId,
                                                     @RequestParam String period) {
        try {
            return ResponseEntity.ok(tools.getBudgetStatus(householdId, categoryId, period));
        } catch (IllegalArgumentException e) {
            // No active budget for the slot — REST sugar over the tool's
            // exception. Scheduler-driven callers treat this as "no-op" rather
            // than retry, so 404 is the right signal here.
            return ResponseEntity.notFound().build();
        }
    }
}
