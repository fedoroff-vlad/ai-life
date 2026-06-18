package dev.fedorov.ailife.agents.finance.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agents.finance.http.GiftBudgetClient;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Inter-agent action endpoint (Stage 4 / Track C1, consumer side). The
 * orchestrator forwards an {@link AgentActionRequest} here when another agent
 * invokes finance — currently the budget-aware gift-recommender flow (D2):
 * calendar-agent gathers the household's gift budget via {@code get_gift_budget}.
 *
 * <p>The action forces {@code householdId} from the envelope (never trusts an
 * args-supplied household) and reads mcp-finance's {@code /internal/gift-budget}
 * (PR93). Always replies with an {@link AgentActionResult} (never an HTTP
 * error): a present budget → {@code {hasGiftBudget:true, amount, currency,
 * remaining?}}; no budget set → {@code {hasGiftBudget:false}}; mcp-finance
 * down → {@code ok=false}. Mirrors calendar-agent's {@code create_event}
 * action (C1c / PR74).
 */
@RestController
public class ActionController {

    private static final Logger log = LoggerFactory.getLogger(ActionController.class);

    private final GiftBudgetClient giftBudget;
    private final ObjectMapper json;

    public ActionController(GiftBudgetClient giftBudget, ObjectMapper json) {
        this.giftBudget = giftBudget;
        this.json = json;
    }

    @PostMapping("/agents/finance/actions/{action}")
    public Mono<AgentActionResult> action(@PathVariable String action,
                                          @RequestBody AgentActionRequest request) {
        if (!"get_gift_budget".equals(action)) {
            return Mono.just(AgentActionResult.error("finance: unknown action '" + action + "'"));
        }
        return getGiftBudget(request);
    }

    private Mono<AgentActionResult> getGiftBudget(AgentActionRequest request) {
        UUID household = request.householdId();
        if (household == null) {
            return Mono.just(AgentActionResult.error("get_gift_budget requires householdId"));
        }
        return giftBudget.fetch(household)
                .map(opt -> {
                    ObjectNode node = json.createObjectNode();
                    if (opt.isPresent()) {
                        var b = opt.get();
                        node.put("hasGiftBudget", true);
                        node.put("amount", b.amount());
                        node.put("currency", b.currency());
                        if (b.remaining() != null) node.put("remaining", b.remaining());
                    } else {
                        // No Gifts category / no active monthly budget — a valid
                        // state, not an error. The coordinator can still propose
                        // gifts without a budget constraint.
                        node.put("hasGiftBudget", false);
                    }
                    return AgentActionResult.ok(node);
                })
                .onErrorResume(e -> {
                    log.warn("get_gift_budget failed (requestedBy={})", request.requestingAgent(), e);
                    return Mono.just(AgentActionResult.error("get_gift_budget failed: " + e.getMessage()));
                });
    }
}
