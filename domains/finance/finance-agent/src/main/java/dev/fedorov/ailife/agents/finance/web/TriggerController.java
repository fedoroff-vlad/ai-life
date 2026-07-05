package dev.fedorov.ailife.agents.finance.web;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.http.MemoryClient;
import dev.fedorov.ailife.agentruntime.http.NotifierClient;
import dev.fedorov.ailife.agentruntime.http.ProfileClient;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.finance.http.BudgetStatusClient;
import dev.fedorov.ailife.agents.finance.http.RecurringClient;
import dev.fedorov.ailife.agents.finance.http.TransactionClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.finance.BudgetStatusResult;
import dev.fedorov.ailife.contracts.finance.FinRecurringDto;
import dev.fedorov.ailife.contracts.finance.FinTransactionDto;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Hit by orchestrator when scheduler-service wakes this agent for a {@code kind}
 * declared in {@link AgentManifest#triggers()}. Resolves the trigger to a
 * {@link Skill}, enriches the wake payload with long-term memory hits, runs the
 * skill (LLM with AGENT.md + SKILL.md bodies as layered system prompts), and
 * fans the generated text out to every user in the wake's household via
 * notifier-service.
 *
 * <p>Memory enrichment is gated soft-fail through {@link MemoryClient} (500 ms
 * timeout, any error → empty list) so memory-service downtime degrades the
 * skill but does not block it.
 *
 * <p>Notifier failures per-user don't block the others — they're logged and the
 * overall response is still 202. The {@code SKIP} sentinel that
 * {@code budget-alerts}'s SKILL.md emits below the alert threshold short-
 * circuits before either profile-service or notifier-service is touched.
 */
@RestController
@RequestMapping("/agents/finance/triggers")
public class TriggerController {

    private static final Logger log = LoggerFactory.getLogger(TriggerController.class);

    static final String SKIP_SENTINEL = "SKIP";
    static final String BUDGET_ALERT_KIND = "budget.alert";
    static final String RECURRING_DUE_KIND = "recurring.due";
    static final String TRANSACTION_UNCATEGORISED_KIND = "transaction.uncategorised";

    private final LlmClient llm;
    private final AgentManifest manifest;
    private final SkillRegistry skills;
    private final ProfileClient profile;
    private final NotifierClient notifier;
    private final MemoryClient memory;
    private final BudgetStatusClient budgetStatus;
    private final RecurringClient recurring;
    private final TransactionClient transactions;
    private final ObjectMapper json;

    public TriggerController(LlmClient llm,
                             AgentManifest manifest,
                             SkillRegistry skills,
                             ProfileClient profile,
                             NotifierClient notifier,
                             MemoryClient memory,
                             BudgetStatusClient budgetStatus,
                             RecurringClient recurring,
                             TransactionClient transactions,
                             ObjectMapper json) {
        this.llm = llm;
        this.manifest = manifest;
        this.skills = skills;
        this.profile = profile;
        this.notifier = notifier;
        this.memory = memory;
        this.budgetStatus = budgetStatus;
        this.recurring = recurring;
        this.transactions = transactions;
        this.json = json;
    }

    @PostMapping("/{kind}")
    public Mono<ResponseEntity<Void>> trigger(@PathVariable String kind,
                                              @RequestBody AgentWakeRequest req) {
        Skill skill = skills.forTrigger(kind).orElse(null);
        if (skill == null) {
            log.warn("no skill bound to trigger kind={} (schedule={})", kind, req.scheduleId());
            return Mono.just(ResponseEntity.notFound().build());
        }
        Mono<ResponseEntity<Void>> pipeline = enrichIfNeeded(kind, req)
                .flatMap(enriched -> runSkill(kind, skill, enriched)
                        .then(maybeAdvanceRecurring(kind, enriched))
                        .then(Mono.fromCallable(() -> ResponseEntity.accepted().<Void>build())));
        return pipeline.onErrorResume(EnrichmentFailedException.class, e -> {
            log.warn("trigger enrichment failed (kind={} schedule={}): {} — returning 503 so scheduler retries",
                    kind, req.scheduleId(), e.getMessage());
            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).<Void>build());
        });
    }

    /**
     * Dispatch enrichment per trigger kind. Each branch follows the same
     * shape: scheduler-shape payload (just the ids needed to look up the live
     * row) → mcp-finance REST passthrough → rewritten payload the skill is
     * trained on; 404 stamps a {@code status: no_active_*} marker so the
     * skill SKIPs; 5xx propagates so the controller returns 503.
     */
    private Mono<AgentWakeRequest> enrichIfNeeded(String kind, AgentWakeRequest req) {
        if (req.payload() == null) return Mono.just(req);
        return switch (kind) {
            case BUDGET_ALERT_KIND -> enrichBudgetAlert(req);
            case RECURRING_DUE_KIND -> enrichRecurringDue(req);
            case TRANSACTION_UNCATEGORISED_KIND -> enrichTransactionUncategorised(req);
            default -> Mono.just(req);
        };
    }

    /**
     * For scheduler-driven {@code budget.alert} wakes whose payload carries only
     * {@code categoryId} + {@code period} (no pre-computed {@code spent}), call
     * mcp-finance to fetch the live budget snapshot and rewrite the payload so
     * the skill prompt sees the same shape PR24a's tests do: {@code categoryName},
     * {@code limit}, {@code spent}, {@code currency}, {@code period}, plus
     * {@code ratio}. Already-enriched payloads (manual callers, the older test
     * fixtures) are passed through unchanged for back-compat.
     */
    private Mono<AgentWakeRequest> enrichBudgetAlert(AgentWakeRequest req) {
        JsonNode payload = req.payload();
        if (payload.hasNonNull("spent")) {
            return Mono.just(req); // already pre-computed
        }
        JsonNode categoryIdNode = payload.get("categoryId");
        JsonNode periodNode = payload.get("period");
        if (categoryIdNode == null || categoryIdNode.isNull()
                || periodNode == null || periodNode.isNull() || periodNode.asText().isBlank()) {
            return Mono.just(req);
        }
        UUID categoryId;
        try {
            categoryId = UUID.fromString(categoryIdNode.asText());
        } catch (IllegalArgumentException e) {
            log.warn("budget.alert payload has malformed categoryId={} (schedule={}), skipping enrichment",
                    categoryIdNode.asText(), req.scheduleId());
            return Mono.just(req);
        }
        if (req.householdId() == null) return Mono.just(req);
        return budgetStatus.fetch(req.householdId(), categoryId, periodNode.asText())
                .map(opt -> opt.map(status -> withPayload(req, buildBudgetAlertPayload(status)))
                        .orElseGet(() -> withPayload(req, markStatus(payload, "no_active_budget"))))
                .onErrorMap(EnrichmentFailedException::wrap);
    }

    /**
     * For scheduler-driven {@code recurring.due} wakes whose payload carries
     * only {@code recurringId} (no pre-computed {@code name}), call mcp-finance
     * to fetch the live recurring row and rewrite the payload so the skill
     * sees {@code name, amount, currency, nextDue, note}. Already-enriched
     * payloads (manual callers) pass through unchanged.
     */
    private Mono<AgentWakeRequest> enrichRecurringDue(AgentWakeRequest req) {
        JsonNode payload = req.payload();
        if (payload.hasNonNull("name")) {
            return Mono.just(req); // already pre-computed
        }
        JsonNode recurringIdNode = payload.get("recurringId");
        if (recurringIdNode == null || recurringIdNode.isNull() || recurringIdNode.asText().isBlank()) {
            return Mono.just(req);
        }
        UUID recurringId;
        try {
            recurringId = UUID.fromString(recurringIdNode.asText());
        } catch (IllegalArgumentException e) {
            log.warn("recurring.due payload has malformed recurringId={} (schedule={}), skipping enrichment",
                    recurringIdNode.asText(), req.scheduleId());
            return Mono.just(req);
        }
        return recurring.fetch(recurringId)
                .map(opt -> opt.map(r -> withPayload(req, buildRecurringDuePayload(r)))
                        .orElseGet(() -> withPayload(req, markStatus(payload, "no_active_recurring"))))
                .onErrorMap(EnrichmentFailedException::wrap);
    }

    /**
     * For scheduler-driven {@code transaction.uncategorised} wakes whose
     * payload carries only {@code transactionId}, fetch the row from
     * mcp-finance and rewrite the payload to {@code amount, currency, note,
     * source, ts} — the shape the {@code transaction-categorizer} skill is
     * trained on. Already-enriched payloads (manual callers) pass through.
     */
    private Mono<AgentWakeRequest> enrichTransactionUncategorised(AgentWakeRequest req) {
        JsonNode payload = req.payload();
        if (payload.hasNonNull("amount")) {
            return Mono.just(req); // already pre-computed
        }
        JsonNode txIdNode = payload.get("transactionId");
        if (txIdNode == null || txIdNode.isNull() || txIdNode.asText().isBlank()) {
            return Mono.just(req);
        }
        UUID txId;
        try {
            txId = UUID.fromString(txIdNode.asText());
        } catch (IllegalArgumentException e) {
            log.warn("transaction.uncategorised payload has malformed transactionId={} (schedule={}), skipping enrichment",
                    txIdNode.asText(), req.scheduleId());
            return Mono.just(req);
        }
        return transactions.fetch(txId)
                .map(opt -> opt.map(tx -> withPayload(req, buildTransactionCategorizerPayload(tx)))
                        .orElseGet(() -> withPayload(req, markStatus(payload, "no_active_transaction"))))
                .onErrorMap(EnrichmentFailedException::wrap);
    }

    private ObjectNode buildTransactionCategorizerPayload(FinTransactionDto tx) {
        ObjectNode out = json.createObjectNode();
        out.put("transactionId", tx.id().toString());
        out.put("amount", tx.amount());
        out.put("currency", tx.currency());
        if (tx.note() != null && !tx.note().isBlank()) out.put("note", tx.note());
        if (tx.source() != null && !tx.source().isBlank()) out.put("source", tx.source());
        if (tx.ts() != null) out.put("ts", tx.ts().toString());
        return out;
    }

    private ObjectNode buildBudgetAlertPayload(BudgetStatusResult status) {
        ObjectNode out = json.createObjectNode();
        out.put("categoryId", status.categoryId().toString());
        out.put("categoryName", status.categoryName());
        out.put("limit", status.limitAmount());
        out.put("spent", status.spent());
        out.put("currency", status.currency());
        out.put("period", status.period());
        if (status.ratio() != null) {
            out.put("ratio", status.ratio());
        }
        return out;
    }

    private ObjectNode buildRecurringDuePayload(FinRecurringDto r) {
        ObjectNode out = json.createObjectNode();
        out.put("recurringId", r.id().toString());
        out.put("name", r.name());
        out.put("amount", r.amount());
        out.put("currency", r.currency());
        if (r.nextDue() != null) out.put("nextDue", r.nextDue().toString());
        if (r.note() != null && !r.note().isBlank()) out.put("note", r.note());
        return out;
    }

    /**
     * mcp-finance returned 404 — the upstream row is gone. Mark the payload so
     * the skill prompt emits SKIP rather than fabricating numbers. We don't
     * abort the LLM call entirely because a future skill version may want to
     * notice "row was deleted" and react.
     */
    private ObjectNode markStatus(JsonNode original, String status) {
        ObjectNode out = original.isObject() ? ((ObjectNode) original).deepCopy()
                : json.createObjectNode();
        out.put("status", status);
        return out;
    }

    /**
     * Post-tick hook: ask mcp-finance to advance {@code fin_recurring.next_due}
     * so {@code list_recurring} stops returning a stale snapshot. Skipped when
     * the upstream row is already gone (enrichment stamped
     * {@code status: no_active_recurring}). Errors are soft-failed — a stale
     * column is cosmetic, the trigger itself already ran.
     */
    private Mono<Void> maybeAdvanceRecurring(String kind, AgentWakeRequest req) {
        if (!RECURRING_DUE_KIND.equals(kind) || req.payload() == null) return Mono.empty();
        JsonNode payload = req.payload();
        if (payload.hasNonNull("status")
                && "no_active_recurring".equals(payload.get("status").asText())) {
            return Mono.empty();
        }
        JsonNode idNode = payload.get("recurringId");
        if (idNode == null || idNode.isNull() || idNode.asText().isBlank()) return Mono.empty();
        UUID recurringId;
        try {
            recurringId = UUID.fromString(idNode.asText());
        } catch (IllegalArgumentException e) {
            return Mono.empty();
        }
        return recurring.advance(recurringId)
                .onErrorResume(e -> {
                    log.warn("recurring advance failed for {} (schedule={}): {}",
                            recurringId, req.scheduleId(), e.toString());
                    return Mono.empty();
                });
    }

    private static AgentWakeRequest withPayload(AgentWakeRequest req, JsonNode payload) {
        return new AgentWakeRequest(req.scheduleId(), req.householdId(), req.agent(),
                req.kind(), payload);
    }

    private static final class EnrichmentFailedException extends RuntimeException {
        EnrichmentFailedException(Throwable cause) { super(cause); }

        static Throwable wrap(Throwable e) {
            return e instanceof EnrichmentFailedException ? e : new EnrichmentFailedException(e);
        }
    }

    private Mono<Void> runSkill(String kind, Skill skill, AgentWakeRequest req) {
        String recallQuery = buildRecallQuery(kind, req.payload());
        return memory.recall(req.householdId(), null, null, recallQuery)
                .flatMap(memories -> dispatch(kind, skill, req, memories));
    }

    private Mono<Void> dispatch(String kind, Skill skill, AgentWakeRequest req,
                                List<RecallMemoryHit> memories) {
        ObjectNode userMsg = json.createObjectNode();
        userMsg.set("payload", req.payload() == null ? json.createObjectNode() : req.payload());
        if (!memories.isEmpty()) {
            userMsg.set("memories", json.valueToTree(memories));
        }

        var chat = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(skill.body()),
                LlmMessage.user(userMsg.toString())));

        return llm.chat(chat).flatMap(resp -> {
            String text = resp.content();
            if (text != null && SKIP_SENTINEL.equalsIgnoreCase(text.trim())) {
                log.info("trigger kind={} skill={} schedule={} memories={} produced SKIP — no notification",
                        kind, skill.name(), req.scheduleId(), memories.size());
                return Mono.empty();
            }
            if (text == null || text.isBlank() || req.householdId() == null) {
                log.info("trigger kind={} skill={} schedule={} memories={} produced {} chars but nothing to fan out",
                        kind, skill.name(), req.scheduleId(), memories.size(),
                        text == null ? 0 : text.length());
                return Mono.empty();
            }
            log.info("trigger kind={} skill={} schedule={} memories={} produced {} chars, fanning out",
                    kind, skill.name(), req.scheduleId(), memories.size(), text.length());
            return profile.usersByHousehold(req.householdId())
                    .flatMap(u -> notifier.notify(u.id(), text)
                            .doOnError(e -> log.warn(
                                    "notify failed for user={} kind={}: {}",
                                    u.id(), kind, e.toString()))
                            .onErrorResume(e -> Mono.empty()))
                    .then();
        });
    }

    /**
     * Build the natural-language query memory-service will embed for recall.
     * Anchored on the wake payload's {@code categoryName} when present (so
     * budget-alerts recall is at least category-aware); falls back to the
     * recurring's {@code name} for {@code recurring.due}; falls back to the
     * trigger kind otherwise.
     */
    static String buildRecallQuery(String kind, JsonNode payload) {
        if (payload != null) {
            JsonNode cat = payload.get("categoryName");
            if (cat != null && !cat.isNull() && !cat.asText().isBlank()) {
                return kind + " for " + cat.asText();
            }
            JsonNode name = payload.get("name");
            if (name != null && !name.isNull() && !name.asText().isBlank()) {
                return kind + " for " + name.asText();
            }
        }
        return kind;
    }
}
