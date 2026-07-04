package dev.fedorov.ailife.agents.finance.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.brief.BriefResponder;
import dev.fedorov.ailife.agentruntime.web.AgentActionController;
import dev.fedorov.ailife.agents.finance.http.GiftBudgetClient;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.finance.GiftBudgetRuleDto;
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
 * args-supplied household). When {@code args.relationship} is supplied (Track D3 /
 * D3c) it first reads the relationship-tiered rule via mcp-finance's
 * {@code /internal/gift-budget-rule} (PR105) — a present rule wins
 * ({@code source:"rule"}); no rule for the tier falls back to the household
 * "Gifts" envelope ({@code /internal/gift-budget}, PR93, {@code source:"envelope"}).
 * With no {@code relationship} it goes straight to the envelope. Always replies
 * with an {@link AgentActionResult} (never an HTTP error): a present budget →
 * {@code {hasGiftBudget:true, amount, currency, remaining?, source}}; no budget
 * set → {@code {hasGiftBudget:false}}; mcp-finance down → {@code ok=false}.
 * Mirrors calendar-agent's {@code create_event} action (C1c / PR74).
 *
 * <p>Also registers the generic <b>{@code brief}</b> read-action (#290, Slice B): it delegates to the
 * shared {@link BriefResponder} so the coordinator can ask finance a focused sub-question and fold the
 * grounded, read-only answer into a multi-domain synthesis. Finance is the first agent to expose it.
 */
@RestController
public class ActionController extends AgentActionController {

    private final GiftBudgetClient giftBudget;
    private final ObjectMapper json;

    public ActionController(GiftBudgetClient giftBudget, BriefResponder briefResponder, ObjectMapper json) {
        super("finance");
        this.giftBudget = giftBudget;
        this.json = json;
        register("get_gift_budget", this::getGiftBudget);
        // Generic read-only cross-agent query (#290, Slice B): the coordinator can ask finance a
        // focused sub-question and fold the grounded answer into a multi-domain synthesis.
        register("brief", briefResponder::answer);
    }

    @PostMapping("/agents/finance/actions/{action}")
    public Mono<AgentActionResult> action(@PathVariable String action,
                                          @RequestBody AgentActionRequest request) {
        return dispatch(action, request);
    }

    private Mono<AgentActionResult> getGiftBudget(AgentActionRequest request) {
        UUID household = request.householdId();
        if (household == null) {
            return Mono.just(AgentActionResult.error("get_gift_budget requires householdId"));
        }
        String relationship = relationshipArg(request);
        return (relationship == null)
                ? envelope(household)
                : giftBudget.fetchRule(household, relationship)
                        .flatMap(opt -> opt.isPresent()
                                ? Mono.just(ruleResult(opt.get(), relationship))
                                : envelope(household));
    }

    /** The household "Gifts" envelope read (D2b), used directly or as the D3c tier fallback. */
    private Mono<AgentActionResult> envelope(UUID household) {
        return giftBudget.fetch(household)
                .map(opt -> {
                    ObjectNode node = json.createObjectNode();
                    if (opt.isPresent()) {
                        var b = opt.get();
                        node.put("hasGiftBudget", true);
                        node.put("amount", b.amount());
                        node.put("currency", b.currency());
                        if (b.remaining() != null) node.put("remaining", b.remaining());
                        node.put("source", "envelope");
                    } else {
                        // No Gifts category / no active monthly budget — a valid
                        // state, not an error. The coordinator can still propose
                        // gifts without a budget constraint.
                        node.put("hasGiftBudget", false);
                    }
                    return AgentActionResult.ok(node);
                });
    }

    /** A matched relationship-tiered rule (D3c). A preference amount has no spend window → no {@code remaining}. */
    private AgentActionResult ruleResult(GiftBudgetRuleDto rule, String relationship) {
        ObjectNode node = json.createObjectNode();
        node.put("hasGiftBudget", true);
        node.put("amount", rule.amount());
        node.put("currency", rule.currency());
        node.put("relationship", relationship);
        node.put("source", "rule");
        return AgentActionResult.ok(node);
    }

    private String relationshipArg(AgentActionRequest request) {
        JsonNode args = request.args();
        if (args == null) return null;
        JsonNode rel = args.get("relationship");
        if (rel == null || rel.isNull()) return null;
        String s = rel.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
