package dev.fedorov.ailife.agents.finance.intent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.intent.CandidateView;
import dev.fedorov.ailife.agentruntime.intent.Nouns;
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
import dev.fedorov.ailife.llm.LlmClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Runs the reactive {@code transaction-delete} intent skill (road-test #486, Track H.2 — the finance
 * per-domain delete hole): delete a logged expense by just chatting ("удали трату про X / последнюю
 * трату"), behind the standing <b>confirm-before-delete</b> gate (a finance principle; see AGENT.md). When
 * {@code IntentRouter} classifies a message as a delete request,
 * {@link dev.fedorov.ailife.agents.finance.web.IntentController} dispatches to {@link #delete}; the
 * confirming reply routes back through {@code ResumeController} to {@link #resume}.
 *
 * <p>The pick→confirm→act loop itself lives in the shared {@link PickConfirmActRunner} (ADR-0004); this
 * class is the finance adapter — it names the target ({@link #nouns}), reads the candidate pool (recent
 * transactions, personal ∪ shared via {@link SpendingReads#households} + a fan-out of
 * {@link TransactionClient#list}), renders a candidate ({@link CandidateView}), and performs the delete
 * ({@link #act} via mcp-finance {@code DELETE /internal/transaction/{id}} — the same reversal the undo
 * primitive uses).
 */
@Component
public class TransactionDeleter implements TargetedActionFlow<FinTransactionDto>, CandidateView<FinTransactionDto> {

    public static final String SKILL_NAME = "transaction-delete";
    /** pendingAction discriminator the finance ResumeController dispatches on. */
    public static final String FLOW = "transaction-delete-confirm";
    private static final int MAX_CANDIDATES = 40;

    private final SpendingReads spendingReads;
    private final TransactionClient transactions;
    private final PickConfirmActRunner<FinTransactionDto> runner;

    public TransactionDeleter(LlmClient llm, AgentManifest manifest, SkillRegistry skills,
                              SpendingReads spendingReads, TransactionClient transactions, ObjectMapper json) {
        this.spendingReads = spendingReads;
        this.transactions = transactions;
        this.runner = new PickConfirmActRunner<>(llm, manifest, skills, json, this);
    }

    /** Turn 1: read the owner's recent transactions, let the LLM pick, and reply with a confirm {@code pendingAction}. */
    public Mono<IntentResponse> delete(NormalizedMessage msg) {
        return runner.pick(msg);
    }

    /** Turn 2: an affirmative deletes the stashed transaction; anything else leaves it. */
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
    public Nouns nouns() {
        return new Nouns("трату", "трат", "трата");
    }

    @Override
    public Mono<List<FinTransactionDto>> candidates(NormalizedMessage msg) {
        // Search everywhere the user can see (personal ∪ shared) so a shared expense is findable too.
        return spendingReads.households(msg.householdId(), msg.userId(), true)
                .flatMap(this::recentUnion);
    }

    /** Recent transactions across the household set, merged newest-first and capped. */
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
    public Mono<Void> act(UUID targetId, JsonNode params) {
        return transactions.delete(targetId).then();
    }

    // ----- CandidateView ----------------------------------------------------------------------------

    @Override
    public UUID id(FinTransactionDto t) {
        return t.id();
    }

    /** User-facing label — its note if any (in «…»), plus an amount + currency. */
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
}
