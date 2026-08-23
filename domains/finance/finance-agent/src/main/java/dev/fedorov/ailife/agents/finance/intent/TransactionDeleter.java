package dev.fedorov.ailife.agents.finance.intent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.finance.http.TransactionClient;
import dev.fedorov.ailife.agents.finance.read.SpendingReads;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.finance.FinTransactionDto;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Runs the reactive {@code transaction-delete} intent skill (road-test #486, Track H.2 — the finance
 * per-domain delete hole): delete a logged expense by just chatting ("удали трату про X / последнюю
 * трату"), behind the standing <b>confirm-before-delete</b> gate (a finance principle; see AGENT.md). When
 * {@link IntentRouter} classifies a message as a delete request, {@link
 * dev.fedorov.ailife.agents.finance.web.IntentController} dispatches here.
 *
 * <p>Two turns over the Stage-4 pending-action lock (mirrors tasks' {@code TaskDeleter} + calendar's
 * {@code EventCanceller}):
 * <ol>
 *   <li>{@link #delete} — read the owner's recent transactions (personal ∪ shared, via the sharing-aware
 *       {@link SpendingReads#households} + a fan-out of {@link TransactionClient#list}), let the LLM
 *       ({@code transaction-delete} SKILL, temperature 0) pick which candidate they mean, and reply with a
 *       {@code pendingAction} asking to confirm the deletion (route-locks to finance). Nothing is deleted
 *       yet. An ambiguous / unmatched target lists / asks instead of guessing.</li>
 *   <li>{@link #resume} — on the reply: an affirmative deletes the stashed transaction via
 *       {@link TransactionClient#delete} (mcp-finance {@code DELETE /internal/transaction/{id}} — the same
 *       reversal the undo primitive uses); anything else leaves it.</li>
 * </ol>
 * Every stage soft-fails to a friendly Russian message (no pendingAction → no lock).
 */
@Component
public class TransactionDeleter {

    private static final Logger log = LoggerFactory.getLogger(TransactionDeleter.class);
    public static final String SKILL_NAME = "transaction-delete";
    /** pendingAction discriminator the finance ResumeController dispatches on. */
    public static final String FLOW = "transaction-delete-confirm";
    private static final int MAX_CANDIDATES = 40;
    private static final Set<String> AFFIRMATIVE = Set.of(
            "да", "ага", "верно", "удали", "удалить", "убери", "убрать", "ок", "окей", "давай", "+",
            "yes", "y", "ok", "delete", "confirm");

    private final LlmClient llm;
    private final AgentManifest manifest;
    private final SkillRegistry skills;
    private final SpendingReads spendingReads;
    private final TransactionClient transactions;
    private final ObjectMapper json;

    public TransactionDeleter(LlmClient llm, AgentManifest manifest, SkillRegistry skills,
                              SpendingReads spendingReads, TransactionClient transactions, ObjectMapper json) {
        this.llm = llm;
        this.manifest = manifest;
        this.skills = skills;
        this.spendingReads = spendingReads;
        this.transactions = transactions;
        this.json = json;
    }

    public Mono<IntentResponse> delete(NormalizedMessage msg) {
        String userText = msg == null ? null : msg.text();
        if (userText == null || userText.isBlank()) {
            return Mono.just(reply("Какую трату удалить?", null));
        }
        if (msg.householdId() == null) {
            return Mono.just(reply("Не знаю, к какому хозяйству относится запрос.", null));
        }
        // Search everywhere the user can see (personal ∪ shared) so a shared expense is findable too.
        return spendingReads.households(msg.householdId(), msg.userId(), true)
                .flatMap(this::recentUnion)
                .flatMap(txs -> resolveAndConfirm(userText, txs))
                .onErrorResume(e -> {
                    log.warn("transaction-delete failed: {}", e.toString());
                    return Mono.just(reply("Не смог найти трату для удаления. Попробуйте ещё раз позже.", null));
                });
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

    private Mono<IntentResponse> resolveAndConfirm(String userText, List<FinTransactionDto> txs) {
        if (txs == null || txs.isEmpty()) {
            return Mono.just(reply("Не нашёл трат, которые можно удалить.", null));
        }
        ObjectNode userMsg = json.createObjectNode();
        userMsg.put("userText", userText);
        userMsg.set("candidates", candidateList(txs));

        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(skillBody()),
                LlmMessage.user(userMsg.toString())), 0.0);

        return llm.chat(req).map(resp -> pickReply(parsePick(resp.content()), txs, resp.model()));
    }

    private IntentResponse pickReply(Pick pick, List<FinTransactionDto> candidates, String model) {
        if (pick == null || pick.indices().isEmpty()) {
            return reply("Не нашёл такую трату. Уточните, что удалить.", model);
        }
        if (pick.indices().size() > 1) {
            StringBuilder sb = new StringBuilder("Нашёл несколько подходящих трат — какую удалить?");
            for (int i : pick.indices()) {
                FinTransactionDto t = candidateAt(candidates, i);
                if (t != null) {
                    sb.append("\n• ").append(describe(t));
                }
            }
            return reply(sb.toString(), model);
        }
        FinTransactionDto target = candidateAt(candidates, pick.indices().get(0));
        if (target == null) {
            return reply("Не нашёл такую трату. Уточните, что удалить.", model);
        }
        String label = describe(target);
        String confirm = "Удалить трату " + label + "? Ответьте «да», чтобы удалить.";
        return new IntentResponse(manifest.name(), confirm, model, pendingAction(target.id(), label));
    }

    /**
     * Resume after the user replies to the confirmation. Affirmative → delete the stashed transaction;
     * anything else → leave it. Either reply carries no pendingAction, so the orchestrator clears the lock.
     */
    public Mono<IntentResponse> resume(ResumeRequest req) {
        JsonNode pending = req.pendingAction();
        UUID txId = txId(pending);
        if (txId == null) {
            return Mono.just(reply("Нечего удалять — повторите запрос, пожалуйста.", null));
        }
        String label = pending.path("label").asString("трату");
        String text = req.message() == null ? null : req.message().text();
        if (!isAffirmative(text)) {
            return Mono.just(reply("Оставил " + label + " без изменений.", null));
        }
        return transactions.delete(txId)
                .map(deleted -> reply("Удалил трату " + label + ".", null))
                .onErrorResume(e -> {
                    log.warn("transaction-delete delete failed for {}: {}", txId, e.toString());
                    return Mono.just(reply(
                            "Не смог удалить " + label + " — возможно, трата уже удалена.", null));
                });
    }

    /** The numbered candidate list handed to the LLM ({@code {n, amount, currency, note?, date}}). */
    private ArrayNode candidateList(List<FinTransactionDto> candidates) {
        ArrayNode arr = json.createArrayNode();
        for (int i = 0; i < candidates.size(); i++) {
            FinTransactionDto t = candidates.get(i);
            ObjectNode node = json.createObjectNode();
            node.put("n", i + 1);
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
            arr.add(node);
        }
        return arr;
    }

    /** Parse the LLM selection: {"pick":n} | {"ambiguous":[n,...]} | {} (none). 1-based indices. */
    private Pick parsePick(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        JsonNode node;
        try {
            node = json.readTree(raw.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
        if (node.hasNonNull("pick") && node.get("pick").isNumber()) {
            return new Pick(List.of(node.get("pick").asInt()));
        }
        JsonNode ambiguous = node.get("ambiguous");
        if (ambiguous != null && ambiguous.isArray()) {
            List<Integer> ns = new ArrayList<>();
            ambiguous.forEach(n -> {
                if (n.isNumber()) {
                    ns.add(n.asInt());
                }
            });
            return new Pick(ns);
        }
        return null;
    }

    private static FinTransactionDto candidateAt(List<FinTransactionDto> candidates, int oneBased) {
        int i = oneBased - 1;
        return (i >= 0 && i < candidates.size()) ? candidates.get(i) : null;
    }

    /** User-facing label for a transaction — its note if any, plus a signed amount + currency. */
    private static String describe(FinTransactionDto t) {
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

    private JsonNode pendingAction(UUID txId, String label) {
        ObjectNode node = json.createObjectNode();
        node.put("flow", FLOW);
        node.put("transactionId", txId.toString());
        node.put("label", label);
        return node;
    }

    private static UUID txId(JsonNode pending) {
        if (pending == null || !pending.hasNonNull("transactionId")) {
            return null;
        }
        try {
            return UUID.fromString(pending.get("transactionId").asString().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isAffirmative(String text) {
        return text != null && AFFIRMATIVE.contains(text.trim().toLowerCase(Locale.ROOT));
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "transaction-delete SKILL.md not loaded — check skills-classpath"));
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }

    /** The resolved selection: one index (pick), several (ambiguous), or empty (none). */
    private record Pick(List<Integer> indices) {
    }
}
