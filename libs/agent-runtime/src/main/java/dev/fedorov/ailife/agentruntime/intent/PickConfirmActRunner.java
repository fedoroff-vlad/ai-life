package dev.fedorov.ailife.agentruntime.intent;

import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The shared <b>pick → confirm → act</b> runner (ADR-0004): the two-turn confirm-act loop over the Stage-4
 * pending-action lock that the five per-domain flows (tasks/finance/notes delete + calendar cancel/move)
 * copy-pasted at ~250 lines each. A domain supplies a {@link TargetedActionFlow} adapter; this runner owns
 * the orchestration, the LLM round-trip + selection parse, the confirm gate, the shared Russian wording,
 * and the resume soft-fail. Constructed per-flow by the domain (it is not an auto-wired bean — it needs the
 * flow adapter); the agent's {@code IntentController}/{@code ResumeController} still dispatch to the domain
 * object, which delegates here.
 *
 * <p>Two turns:
 * <ol>
 *   <li>{@link #pick} — validate, read {@link TargetedActionFlow#candidates}, ask the LLM
 *       ({@code manifest.body} + the flow's SKILL, temperature 0) to pick one candidate, and — on a single
 *       complete match — reply with a {@code pendingAction} asking to confirm (route-locks; <b>acts on
 *       nothing</b>). Empty pool / none / ambiguous / a {@link TargetedActionFlow#missing} re-ask all reply
 *       without a lock.</li>
 *   <li>{@link #resume} — on the reply: an affirmative runs {@link TargetedActionFlow#act}; anything else
 *       leaves it. Either reply carries no {@code pendingAction}, so the orchestrator clears the lock.</li>
 * </ol>
 * Every stage soft-fails to a friendly Russian message.
 */
public final class PickConfirmActRunner<T> {

    private static final Logger log = LoggerFactory.getLogger(PickConfirmActRunner.class);

    /** Cap on candidates handed to the LLM — a defensive bound mirroring the pre-lift per-flow constant. */
    private static final int MAX_CANDIDATES = 40;

    /** The affirmative words every confirm-act flow accepts on resume; a flow may add more (notes: "забудь"). */
    public static final Set<String> DEFAULT_AFFIRMATIVE = Set.of(
            "да", "ага", "верно", "удали", "удалить", "убери", "убрать", "ок", "окей", "давай", "+",
            "yes", "y", "ok", "delete", "confirm");

    private final LlmClient llm;
    private final AgentManifest manifest;
    private final SkillRegistry skills;
    private final ObjectMapper json;
    private final TargetedActionFlow<T> flow;
    private final Set<String> affirmative;

    public PickConfirmActRunner(LlmClient llm, AgentManifest manifest, SkillRegistry skills,
                                ObjectMapper json, TargetedActionFlow<T> flow) {
        this.llm = llm;
        this.manifest = manifest;
        this.skills = skills;
        this.json = json;
        this.flow = flow;
        Set<String> aff = new HashSet<>(DEFAULT_AFFIRMATIVE);
        aff.addAll(flow.extraAffirmatives());
        this.affirmative = Set.copyOf(aff);
    }

    // ----- turn 1: pick + confirm -------------------------------------------------------------------

    /** Read candidates, let the LLM pick, and (single complete match) reply with a confirm {@code pendingAction}. */
    public Mono<IntentResponse> pick(NormalizedMessage msg) {
        String userText = msg == null ? null : msg.text();
        if (userText == null || userText.isBlank()) {
            return Mono.just(reply("Какую " + acc() + " удалить?", null));
        }
        if (msg.householdId() == null) {
            return Mono.just(reply("Не знаю, к какому хозяйству относится запрос.", null));
        }
        return flow.candidates(msg)
                .flatMap(items -> resolveAndConfirm(userText, items))
                .onErrorResume(e -> {
                    log.warn("{} failed: {}", flow.flow(), e.toString());
                    return Mono.just(reply(
                            "Не смог найти " + acc() + " для удаления. Попробуйте ещё раз позже.", null));
                });
    }

    private Mono<IntentResponse> resolveAndConfirm(String userText, List<T> items) {
        if (items == null || items.isEmpty()) {
            return Mono.just(reply("Не нашёл " + genPl() + ", которые можно удалить.", null));
        }
        List<T> candidates = items.size() > MAX_CANDIDATES ? items.subList(0, MAX_CANDIDATES) : items;

        ObjectNode userMsg = json.createObjectNode();
        userMsg.put("userText", userText);
        userMsg.set("candidates", candidateList(candidates));

        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(skillBody()),
                LlmMessage.user(userMsg.toString())), 0.0);

        return llm.chat(req).map(resp -> pickReply(parsePick(resp.content()), candidates, resp.model()));
    }

    private IntentResponse pickReply(Pick pick, List<T> candidates, String model) {
        CandidateView<T> view = flow.view();
        if (pick == null || pick.indices().isEmpty()) {
            return reply("Не нашёл такую " + acc() + ". Уточните, что удалить.", model);
        }
        if (pick.indices().size() > 1) {
            StringBuilder sb = new StringBuilder("Нашёл несколько подходящих " + genPl() + " — какую удалить?");
            for (int i : pick.indices()) {
                T t = candidateAt(candidates, i);
                if (t != null) {
                    sb.append("\n• ").append(view.label(t));
                }
            }
            return reply(sb.toString(), model);
        }
        T target = candidateAt(candidates, pick.indices().get(0));
        if (target == null) {
            return reply("Не нашёл такую " + acc() + ". Уточните, что удалить.", model);
        }
        // Completeness gate (move/edit): resolved a target but a required field is missing → re-ask, no lock.
        Optional<String> missing = flow.missing(pick.raw());
        if (missing.isPresent()) {
            return reply(missing.get(), model);
        }
        String label = view.label(target);
        String confirm = "Удалить " + acc() + " " + label + "? Ответьте «да», чтобы удалить.";
        return new IntentResponse(manifest.name(), confirm, model, pendingAction(view.id(target), label, pick.raw()));
    }

    // ----- turn 2: resume ---------------------------------------------------------------------------

    /** Resume the confirmation: affirmative → {@link TargetedActionFlow#act}; anything else leaves it. No lock either way. */
    public Mono<IntentResponse> resume(ResumeRequest req) {
        JsonNode pending = req == null ? null : req.pendingAction();
        UUID targetId = targetId(pending);
        if (targetId == null) {
            return Mono.just(reply("Нечего удалять — повторите запрос, пожалуйста.", null));
        }
        String label = pending.path(flow.labelField()).asString(acc());
        String text = req.message() == null ? null : req.message().text();
        if (!isAffirmative(text)) {
            return Mono.just(reply("Оставил " + label + " без изменений.", null));
        }
        JsonNode params = pending.path("params");
        return flow.act(targetId, params.isMissingNode() ? null : params)
                .then(Mono.just(reply("Удалил " + acc() + " " + label + ".", null)))
                .onErrorResume(e -> {
                    log.warn("{} act failed for {}: {}", flow.flow(), targetId, e.toString());
                    return Mono.just(reply(
                            "Не смог удалить " + label + " — возможно, " + nom() + " уже удалена.", null));
                });
    }

    // ----- helpers ----------------------------------------------------------------------------------

    /** The numbered candidate list handed to the LLM: {@code {n, <domain fields via view.describe>}}. */
    private ArrayNode candidateList(List<T> candidates) {
        CandidateView<T> view = flow.view();
        ArrayNode arr = json.createArrayNode();
        for (int i = 0; i < candidates.size(); i++) {
            ObjectNode node = json.createObjectNode();
            node.put("n", i + 1);
            view.describe(node, candidates.get(i));
            arr.add(node);
        }
        return arr;
    }

    /** Parse the LLM selection: {@code {"pick":n}} | {@code {"ambiguous":[n,…]}} | {@code {}} (none). 1-based. */
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
            return new Pick(List.of(node.get("pick").asInt()), node);
        }
        JsonNode ambiguous = node.get("ambiguous");
        if (ambiguous != null && ambiguous.isArray()) {
            List<Integer> ns = new ArrayList<>();
            ambiguous.forEach(n -> {
                if (n.isNumber()) {
                    ns.add(n.asInt());
                }
            });
            return new Pick(ns, node);
        }
        return null;
    }

    private T candidateAt(List<T> candidates, int oneBased) {
        int i = oneBased - 1;
        return (i >= 0 && i < candidates.size()) ? candidates.get(i) : null;
    }

    /** {@code {flow, <idField>:id, <labelField>:label[, params]}} — params carries the LLM's extra fields (move). */
    private ObjectNode pendingAction(UUID targetId, String label, JsonNode pick) {
        ObjectNode node = json.createObjectNode();
        node.put("flow", flow.flow());
        node.put(flow.idField(), targetId.toString());
        node.put(flow.labelField(), label);
        ObjectNode params = extraParams(pick);
        if (!params.isEmpty()) {
            node.set("params", params);
        }
        return node;
    }

    /** The selection node's fields other than the index keys — the move/edit payload threaded into the lock. */
    private ObjectNode extraParams(JsonNode pick) {
        ObjectNode params = json.createObjectNode();
        if (pick != null && pick.isObject()) {
            pick.properties().forEach(entry -> {
                String key = entry.getKey();
                if (!"pick".equals(key) && !"ambiguous".equals(key)) {
                    params.set(key, entry.getValue());
                }
            });
        }
        return params;
    }

    private UUID targetId(JsonNode pending) {
        if (pending == null || !pending.hasNonNull(flow.idField())) {
            return null;
        }
        try {
            return UUID.fromString(pending.get(flow.idField()).asString().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean isAffirmative(String text) {
        return text != null && affirmative.contains(text.trim().toLowerCase(Locale.ROOT));
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> flow.skillName().equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        flow.skillName() + " SKILL.md not loaded — check skills-classpath"));
    }

    private String acc() {
        return flow.nouns().accusative();
    }

    private String genPl() {
        return flow.nouns().genitivePlural();
    }

    private String nom() {
        return flow.nouns().nominative();
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }

    /** The resolved selection: one index (pick), several (ambiguous), or empty (none); {@code raw} = the LLM node. */
    private record Pick(List<Integer> indices, JsonNode raw) {
    }
}
