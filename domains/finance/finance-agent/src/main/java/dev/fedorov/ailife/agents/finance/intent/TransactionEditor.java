package dev.fedorov.ailife.agents.finance.intent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.intent.CandidateView;
import dev.fedorov.ailife.agentruntime.intent.Phrasing;
import dev.fedorov.ailife.agentruntime.intent.PickConfirmActRunner;
import dev.fedorov.ailife.agentruntime.intent.TargetedActionFlow;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.finance.http.TransactionClient;
import dev.fedorov.ailife.agents.finance.read.SpendingReads;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.finance.FinTransactionDto;
import dev.fedorov.ailife.contracts.finance.UpdateTransactionInput;
import dev.fedorov.ailife.llm.LlmClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Runs the reactive {@code transaction-edit} intent skill (road-test #486, Track H.2 — the finance
 * per-domain <b>edit</b> hole): fix a logged expense's <b>amount</b> or <b>note</b> by just chatting
 * ("исправь сумму последней траты на 550", "поправь заметку у траты про кофе"), behind the standing
 * <b>confirm-before-change</b> gate (a finance principle; see AGENT.md). When {@code IntentRouter}
 * classifies a message as an {@code edit} request, it dispatches to {@link #edit}; the confirming reply
 * routes back through {@code ResumeController} to {@link #resume}.
 *
 * <p>The pick→confirm→act loop itself lives in the shared {@link PickConfirmActRunner} (ADR-0004); this
 * class is the finance-edit adapter (the sibling of tasks' {@code TaskEditor} + notes' {@code NoteEditor}).
 * The LLM picks the target transaction <b>and</b> extracts the change (a new amount magnitude / note),
 * threaded through the {@code pendingAction}. A picked transaction with no stated change re-asks
 * ({@link #missing}); the terminal {@link #act} re-reads the row to keep the sign convention
 * (expense&lt;0 / income&gt;0) — it applies the target's existing sign to the new magnitude — and PUTs only
 * the changed fields via mcp-finance {@code PUT /internal/transaction/{id}}. Re-categorising is a separate
 * verb (needs a category name→id resolution) and is a queued follow-up, not this flow.
 */
@Component
public class TransactionEditor
        implements TargetedActionFlow<FinTransactionDto>, CandidateView<FinTransactionDto>, Phrasing<FinTransactionDto> {

    public static final String SKILL_NAME = "transaction-edit";
    /** pendingAction discriminator the finance ResumeController dispatches on. */
    public static final String FLOW = "transaction-edit-confirm";
    private static final int MAX_CANDIDATES = 40;

    private final SpendingReads spendingReads;
    private final TransactionClient transactions;
    private final PickConfirmActRunner<FinTransactionDto> runner;

    public TransactionEditor(LlmClient llm, AgentManifest manifest, SkillRegistry skills,
                             SpendingReads spendingReads, TransactionClient transactions, ObjectMapper json) {
        this.spendingReads = spendingReads;
        this.transactions = transactions;
        this.runner = new PickConfirmActRunner<>(llm, manifest, skills, json, this);
    }

    /** Turn 1: read the owner's recent transactions, let the LLM pick + extract the change, and reply with a confirm. */
    public Mono<IntentResponse> edit(NormalizedMessage msg) {
        return runner.pick(msg);
    }

    /** Turn 2: an affirmative applies the stashed change; anything else leaves it. */
    public Mono<IntentResponse> resume(ResumeRequest req) {
        return runner.resume(req);
    }

    // ----- TargetedActionFlow -----------------------------------------------------------------------

    @Override
    public String skillName() {
        return SKILL_NAME;
    }

    @Override
    public String flow() {
        return FLOW;
    }

    @Override
    public String idField() {
        return "transactionId";
    }

    @Override
    public Set<String> extraAffirmatives() {
        return Set.of("исправь", "исправить", "поправь", "сохрани", "сохранить", "save");
    }

    @Override
    public Mono<List<FinTransactionDto>> candidates(NormalizedMessage msg) {
        // Search everywhere the user can see (personal ∪ shared) so a shared expense is editable too.
        return spendingReads.households(msg.householdId(), msg.userId(), true)
                .flatMap(this::recentUnion);
    }

    /** Recent transactions across the household set, merged newest-first and capped (mirrors TransactionDeleter). */
    private Mono<List<FinTransactionDto>> recentUnion(List<UUID> households) {
        return Flux.fromIterable(households)
                .flatMap(h -> transactions.list(h, MAX_CANDIDATES))
                .collectList()
                .map(perHousehold -> {
                    List<FinTransactionDto> all = new ArrayList<>();
                    perHousehold.forEach(all::addAll);
                    all.sort(Comparator.comparing(
                            (FinTransactionDto t) -> t.ts() == null ? Instant.EPOCH : t.ts()).reversed());
                    return all.size() > MAX_CANDIDATES ? all.subList(0, MAX_CANDIDATES) : all;
                });
    }

    @Override
    public CandidateView<FinTransactionDto> view() {
        return this;
    }

    @Override
    public Phrasing<FinTransactionDto> phrasing() {
        return this;
    }

    /** Picked a transaction but the user did not say what to change → ask, without a lock. */
    @Override
    public Optional<String> missing(FinTransactionDto target, JsonNode pick) {
        return hasChange(pick)
                ? Optional.empty()
                : Optional.of("Что изменить в трате " + label(target) + "? Новую сумму или заметку.");
    }

    /** The resume needs at least one stashed change, not just the id. */
    @Override
    public boolean readyToAct(JsonNode pending) {
        return hasChange(pending);
    }

    @Override
    public Mono<Void> act(UUID targetId, JsonNode pending) {
        BigDecimal newAmountMag = decimal(pending, "newAmount");
        String newNote = text(pending, "newNote");
        // Re-read the row so the new amount keeps the transaction's sign (expense<0 / income>0) — the agent
        // owns sign discipline, so we never trust the LLM to sign it. A missing row → actFailed wording.
        return transactions.fetch(targetId)
                .flatMap(opt -> opt
                        .map(tx -> transactions.update(targetId, new UpdateTransactionInput(
                                        targetId, null, null, null,
                                        signed(newAmountMag, tx.amount()), null, null, newNote))
                                .then())
                        .orElseGet(() -> Mono.error(new IllegalStateException("transaction gone: " + targetId))));
    }

    /** Apply the existing row's sign to the new magnitude (default: keep as expense when the sign is unknown). */
    private static BigDecimal signed(BigDecimal magnitude, BigDecimal existing) {
        if (magnitude == null) {
            return null;
        }
        BigDecimal abs = magnitude.abs();
        boolean income = existing != null && existing.signum() > 0;
        return income ? abs : abs.negate();
    }

    // ----- CandidateView ----------------------------------------------------------------------------

    @Override
    public UUID id(FinTransactionDto t) {
        return t.id();
    }

    /** User-facing label — its note if any (in «…»), plus an amount + currency (mirrors TransactionDeleter). */
    @Override
    public String label(FinTransactionDto t) {
        StringBuilder sb = new StringBuilder();
        if (t.note() != null && !t.note().isBlank()) {
            sb.append("«").append(t.note()).append("»");
        }
        BigDecimal amount = t.amount();
        if (amount != null) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append("на ").append(amount.abs().toPlainString());
            if (t.currency() != null) {
                sb.append(" ").append(t.currency());
            }
        }
        return sb.length() == 0 ? "эту трату" : sb.toString();
    }

    @Override
    public void describe(ObjectNode node, FinTransactionDto t) {
        if (t.amount() != null) {
            node.put("amount", t.amount().toPlainString());
        }
        if (t.currency() != null) {
            node.put("currency", t.currency());
        }
        if (t.note() != null && !t.note().isBlank()) {
            node.put("note", t.note());
        }
        if (t.ts() != null) {
            node.put("date", t.ts().toString());
        }
    }

    // ----- Phrasing ---------------------------------------------------------------------------------

    @Override
    public String askWhich() {
        return "Какую трату исправить и что изменить?";
    }

    @Override
    public String noHousehold() {
        return "Не понял, в каком бюджете искать трату.";
    }

    @Override
    public String emptyPool() {
        return "Не нашёл трат, которые можно изменить.";
    }

    @Override
    public String noMatch() {
        return "Не нашёл такую трату. Уточните, что изменить.";
    }

    @Override
    public String readFailed() {
        return "Не смог найти трату для правки. Попробуйте ещё раз позже.";
    }

    @Override
    public String notReady() {
        return "Нечего менять — повторите запрос, пожалуйста.";
    }

    @Override
    public String ambiguous(List<FinTransactionDto> picks) {
        StringBuilder sb = new StringBuilder("Нашёл несколько подходящих трат — какую изменить?");
        for (FinTransactionDto t : picks) {
            sb.append("\n• ").append(label(t));
        }
        return sb.toString();
    }

    @Override
    public String confirm(FinTransactionDto target, JsonNode pick) {
        BigDecimal newAmount = decimal(pick, "newAmount");
        StringBuilder sb = new StringBuilder("Изменить трату ").append(label(target));
        if (newAmount != null) {
            sb.append(" → сумма ").append(newAmount.abs().toPlainString());
            if (target.currency() != null) {
                sb.append(" ").append(target.currency());
            }
        }
        if (text(pick, "newNote") != null) {
            sb.append(" (новая заметка)");
        }
        return sb.append("? Ответьте «да», чтобы сохранить.").toString();
    }

    @Override
    public String declined(JsonNode pending) {
        return "Оставил " + labelOf(pending) + " без изменений.";
    }

    @Override
    public String done(JsonNode pending) {
        return "Изменил трату " + labelOf(pending) + ".";
    }

    @Override
    public String actFailed(JsonNode pending) {
        return "Не смог изменить " + labelOf(pending) + " — возможно, трата уже удалена.";
    }

    private static boolean hasChange(JsonNode node) {
        return decimal(node, "newAmount") != null || text(node, "newNote") != null;
    }

    private static String labelOf(JsonNode pending) {
        return pending.path("label").asString("эту трату");
    }

    /** A present, non-blank string field, else null. */
    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String v = node.get(field).asString().strip();
        return v.isEmpty() ? null : v;
    }

    /** A present, parseable numeric field (accepts a JSON number or a numeric string), else null. */
    private static BigDecimal decimal(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        try {
            return new BigDecimal(node.get(field).asString().strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
