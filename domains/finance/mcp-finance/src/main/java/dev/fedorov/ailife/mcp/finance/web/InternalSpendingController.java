package dev.fedorov.ailife.mcp.finance.web;

import dev.fedorov.ailife.contracts.finance.SpendingByCategoryInput;
import dev.fedorov.ailife.contracts.finance.SpendingByCategoryRow;
import dev.fedorov.ailife.mcp.finance.tools.FinanceMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Non-MCP REST passthrough so an agent can read the spend-by-category aggregate by
 * plain HTTP, without the MCP / LLM tax. Delegates straight to the
 * {@code spending_by_category} tool, so the same {@code [from, to)} window + sign /
 * {@code kind} semantics apply. Used by finance-agent's {@code financial-advisor}
 * flow, which gathers a spending snapshot before the LLM synthesis. Mirrors
 * {@link InternalBudgetController}.
 *
 * <p>The MCP tool stays the right entry point for an LLM-driven question; this is
 * the deterministic gather path a Coordinator step calls.
 */
@RestController
@RequestMapping("/internal/spending-by-category")
public class InternalSpendingController {

    private final FinanceMcpTools tools;

    public InternalSpendingController(FinanceMcpTools tools) {
        this.tools = tools;
    }

    @GetMapping
    public ResponseEntity<?> spending(@RequestParam UUID householdId,
                                      @RequestParam Instant from,
                                      @RequestParam Instant to,
                                      @RequestParam(required = false) String kind) {
        try {
            List<SpendingByCategoryRow> rows = tools.spendingByCategory(
                    new SpendingByCategoryInput(householdId, from, to, kind));
            return ResponseEntity.ok(rows);
        } catch (IllegalArgumentException e) {
            // Bad window (e.g. to <= from) or missing field — the tool's guard,
            // surfaced as 400 rather than a 500.
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
