package dev.fedorov.ailife.mcp.finance.web;

import dev.fedorov.ailife.contracts.finance.GiftBudgetRuleDto;
import dev.fedorov.ailife.mcp.finance.tools.FinanceMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Non-MCP REST passthrough so finance-agent's {@code get_gift_budget} action
 * (Stage 4 / Track D3) can read a relationship-tiered gift-budget rule by plain
 * HTTP without the MCP / LLM tax. Mirrors {@link InternalGiftBudgetController}:
 * no rule for the tier → 404, which the calling agent treats as "no tier rule"
 * and falls back to the household "Gifts" envelope.
 */
@RestController
@RequestMapping("/internal/gift-budget-rule")
public class InternalGiftBudgetRuleController {

    private final FinanceMcpTools tools;

    public InternalGiftBudgetRuleController(FinanceMcpTools tools) {
        this.tools = tools;
    }

    @GetMapping
    public ResponseEntity<GiftBudgetRuleDto> giftBudgetRule(@RequestParam UUID householdId,
                                                            @RequestParam String relationship) {
        try {
            return ResponseEntity.ok(tools.getGiftBudgetRule(householdId, relationship));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
