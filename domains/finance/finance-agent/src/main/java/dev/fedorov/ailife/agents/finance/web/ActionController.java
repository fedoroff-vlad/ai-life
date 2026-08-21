package dev.fedorov.ailife.agents.finance.web;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.brief.BriefResponder;
import dev.fedorov.ailife.agentruntime.web.AgentActionController;
import dev.fedorov.ailife.agents.finance.http.GiftBudgetClient;
import dev.fedorov.ailife.agents.finance.http.SpendingClient;
import dev.fedorov.ailife.agents.finance.http.TransactionClient;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.finance.FinTransactionDto;
import dev.fedorov.ailife.contracts.finance.GiftBudgetRuleDto;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.format.DateTimeParseException;
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
    private final SpendingClient spending;
    private final TransactionClient transactions;
    private final ObjectMapper json;

    public ActionController(GiftBudgetClient giftBudget, SpendingClient spending,
                            TransactionClient transactions,
                            BriefResponder briefResponder, ObjectMapper json) {
        super("finance");
        this.giftBudget = giftBudget;
        this.spending = spending;
        this.transactions = transactions;
        this.json = json;
        register("get_gift_budget", this::getGiftBudget);
        // "Отмени последнее" reversal (#486, Track H): delete a just-written transaction by its stored id
        // and confirm; a missing/already-deleted row surfaces an honest ok=false (never a silent no-op).
        register("undo", this::undo);
        // Structured spend-by-category read over [from,to] for another agent's own synthesis (the
        // briefing digest's finance section). Unlike `brief` this returns the raw rows, not LLM prose —
        // the caller keeps its exact numbers. Read-only, householdId forced from the envelope.
        register("spend_snapshot", this::spendSnapshot);
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

    /**
     * Structured spend-by-category read over the {@code args.from}..{@code args.to} window (ISO-8601
     * instants), for a caller that folds the raw rows into its own synthesis (the briefing digest). Ok →
     * {@code {spending:[SpendingByCategoryRow…], from, to}}; missing household / bad window → {@code ok=false};
     * mcp-finance down → {@code ok=false} (the digest soft-fails that section). No LLM, no writes.
     */
    private Mono<AgentActionResult> spendSnapshot(AgentActionRequest request) {
        UUID household = request.householdId();
        if (household == null) {
            return Mono.just(AgentActionResult.error("spend_snapshot requires householdId"));
        }
        Instant from = instantArg(request, "from");
        Instant to = instantArg(request, "to");
        if (from == null || to == null) {
            return Mono.just(AgentActionResult.error("spend_snapshot requires ISO-8601 args.from and args.to"));
        }
        return spending.spendingByCategory(household, from, to)
                .map(rows -> {
                    ObjectNode node = json.createObjectNode();
                    node.set("spending", json.valueToTree(rows));
                    node.put("from", from.toString());
                    node.put("to", to.toString());
                    return AgentActionResult.ok(node);
                })
                .onErrorResume(e -> Mono.just(AgentActionResult.error("spend_snapshot failed: " + e.getMessage())));
    }

    private Instant instantArg(AgentActionRequest request, String field) {
        JsonNode args = request.args();
        if (args == null) return null;
        JsonNode v = args.get(field);
        if (v == null || v.isNull()) return null;
        try {
            return Instant.parse(v.asString().trim());
        } catch (DateTimeParseException e) {
            return null;
        }
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

    /** Reverse a just-written transaction: delete it by the stored id and confirm (#486, Track H). */
    private Mono<AgentActionResult> undo(AgentActionRequest request) {
        UUID txId = uuidArg(request, "transactionId");
        if (txId == null) {
            return Mono.just(AgentActionResult.error("undo requires args.transactionId"));
        }
        return transactions.delete(txId)
                .map(dto -> {
                    ObjectNode node = json.createObjectNode();
                    node.put("message", "Удалил трату" + describe(dto) + ".");
                    return AgentActionResult.ok(node);
                })
                .onErrorResume(e -> Mono.just(AgentActionResult.error(
                        "Не нашёл трату для отмены — возможно, она уже удалена.")));
    }

    /** A short user-facing tail naming the reversed transaction — its note, else amount + currency. */
    private static String describe(FinTransactionDto dto) {
        if (dto.note() != null && !dto.note().isBlank()) {
            return ": «" + dto.note() + "»";
        }
        if (dto.amount() != null && dto.currency() != null) {
            return " на " + dto.amount().abs().toPlainString() + " " + dto.currency();
        }
        return "";
    }

    private UUID uuidArg(AgentActionRequest request, String field) {
        JsonNode args = request.args();
        if (args == null) {
            return null;
        }
        JsonNode v = args.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        try {
            return UUID.fromString(v.asString().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String relationshipArg(AgentActionRequest request) {
        JsonNode args = request.args();
        if (args == null) return null;
        JsonNode rel = args.get("relationship");
        if (rel == null || rel.isNull()) return null;
        String s = rel.asString().trim();
        return s.isEmpty() ? null : s;
    }
}
