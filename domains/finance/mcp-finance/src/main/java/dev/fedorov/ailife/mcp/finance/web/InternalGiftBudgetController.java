package dev.fedorov.ailife.mcp.finance.web;

import dev.fedorov.ailife.contracts.finance.GiftBudgetResult;
import dev.fedorov.ailife.mcp.finance.tools.FinanceMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Non-MCP REST passthrough so finance-agent's {@code get_gift_budget} action
 * (Stage 4 / Track D, D2b) can read the household's gift-spending envelope by
 * plain HTTP without the MCP / LLM tax. The "Gifts" expense-category monthly
 * budget is the MVP envelope; see {@link FinanceMcpTools#getGiftBudget}.
 * Mirrors {@link InternalBudgetController}: no active gift budget → 404, which
 * the calling agent treats as "no budget set" rather than an error.
 */
@RestController
@RequestMapping("/internal/gift-budget")
public class InternalGiftBudgetController {

    private final FinanceMcpTools tools;

    public InternalGiftBudgetController(FinanceMcpTools tools) {
        this.tools = tools;
    }

    @GetMapping
    public ResponseEntity<GiftBudgetResult> giftBudget(@RequestParam UUID householdId) {
        try {
            return ResponseEntity.ok(tools.getGiftBudget(householdId));
        } catch (IllegalArgumentException e) {
            // No Gifts category or no active monthly budget on it — REST sugar
            // over the tool's exception, same 404 contract as budget-status.
            return ResponseEntity.notFound().build();
        }
    }
}
