package dev.fedorov.ailife.agents.coordinator.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.coordinate.CoordinationResult;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.http.MemoryClient;
import dev.fedorov.ailife.agents.coordinator.config.CoordinatorAgentProperties;
import dev.fedorov.ailife.agents.coordinator.flow.SufficiencyAssessor.Assessment;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The cross-cutting synthesis engine (#290, Slice A). Both the reactive intent path and the proactive
 * trigger path funnel through one {@link #run} method: <b>gather</b> relevant context from the second
 * brain, then a single <b>synthesis</b> on the shared {@link Coordinator}. It owns no domain data and
 * binds nothing domain-specific — Slice A gathers long-term memory recall only; Slice B will add live
 * cross-agent answers to the same {@link #gatherFor} map without touching the entries or {@link #run}.
 *
 * <p><b>Bounded confidence loop (Slice E-later):</b> {@link #gatherFor} builds the gather map and
 * {@link #synthesize} runs the one LLM call; {@link #run} now chains them inside a bounded
 * {@code gather → synthesize → self-check → maybe-re-gather} loop. After each synthesis a cheap FAST
 * {@link SufficiencyAssessor} judges whether the draft is grounded and complete; when it is not, and the
 * {@code coordinator-agent.max-rounds} budget still allows, the loop re-gathers with a sharpened focus
 * (memory re-recall + specialist re-plan) and re-synthesizes, folding the prior draft into the payload so
 * the model refines rather than restarts. {@code max-rounds: 1} keeps the one-shot behaviour exactly (the
 * self-check is never called). Both entry points and the controllers stay unchanged.
 *
 * <p><b>Model-agnostic:</b> the synthesis is one {@link LlmChannel#DEFAULT} call whose quality scales
 * with whatever provider llm-gateway is pointed at (mock / local / API) — no code assumes model
 * strength, and per-source soft-fail is inherited from the {@link Coordinator}.
 *
 * <p><b>Slice B2:</b> {@link #gatherFor} now folds in a second source alongside memory —
 * {@link SpecialistBriefs}, the live specialist-gather leg (plan relevant specialists → invoke each
 * one's read-only {@code brief} through the hub). Both sources soft-fail independently inside the
 * {@link Coordinator}, so a memory-only or specialist-only synthesis is a natural degradation.
 */
@Component
public class MultiDomainCoordinator {

    private static final Logger log = LoggerFactory.getLogger(MultiDomainCoordinator.class);

    /** Recall query for a proactive surface with no explicit focus. */
    private static final String DEFAULT_SURFACE_FOCUS =
            "a useful idea, connection, or reminder worth surfacing to the owner right now";

    /** Precision over volume: a proactive surface shorter than this is treated as "nothing worth sending". */
    private static final int MIN_SURFACE_CHARS = 12;

    private final Coordinator coordinator;
    private final MemoryClient memory;
    private final SpecialistBriefs specialistBriefs;
    private final SufficiencyAssessor assessor;
    private final CoordinatorAgentProperties props;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public MultiDomainCoordinator(Coordinator coordinator, MemoryClient memory,
                                  SpecialistBriefs specialistBriefs, SufficiencyAssessor assessor,
                                  CoordinatorAgentProperties props,
                                  AgentManifest manifest, ObjectMapper json) {
        this.coordinator = coordinator;
        this.memory = memory;
        this.specialistBriefs = specialistBriefs;
        this.assessor = assessor;
        this.props = props;
        this.manifest = manifest;
        this.json = json;
    }

    /** Reactive entry: a cross-cutting user message → one grounded synthesis in an {@link IntentResponse}. */
    public Mono<IntentResponse> handle(NormalizedMessage msg) {
        String query = msg.text() == null ? "" : msg.text();
        ObjectNode payload = json.createObjectNode();
        payload.put("userText", query);
        return run(msg.householdId(), msg.userId(), payload, query)
                .map(r -> reply(r.text(), r.llmModel()))
                .onErrorResume(e -> {
                    log.warn("coordinator synthesis failed: {}", e.toString());
                    return Mono.just(reply("Не удалось собрать ответ. Попробуйте позже.", null));
                });
    }

    /**
     * Proactive entry: gather + synthesize a surface for the owner. Returns the raw
     * {@link CoordinationResult} so the caller ({@code TriggerController}) applies the
     * {@link #isWorthSurfacing relevance gate} and delivery — precision over volume.
     */
    public Mono<CoordinationResult> surface(UUID household, UUID user, String focus) {
        String query = (focus == null || focus.isBlank()) ? DEFAULT_SURFACE_FOCUS : focus;
        ObjectNode payload = json.createObjectNode();
        payload.put("mode", "proactive-surface");
        payload.put("focus", query);
        return run(household, user, payload, query);
    }

    /**
     * The bounded {@code gather → synthesize → self-check → maybe-re-gather} core both entries share.
     * The first round is always run; each further round happens only when the {@link SufficiencyAssessor}
     * judges the current draft under-confident <b>and</b> the {@code max-rounds} budget still allows it.
     */
    private Mono<CoordinationResult> run(UUID household, UUID user, JsonNode payload, String query) {
        return round(household, user, payload, query, query, props.getMaxRounds());
    }

    /**
     * One synthesis round plus the loop decision. {@code effectiveQuery} is the (possibly sharpened) query
     * driving this round's gather; {@code originalQuery} anchors the self-check on the owner's real ask.
     */
    private Mono<CoordinationResult> round(UUID household, UUID user, JsonNode payload,
                                           String originalQuery, String effectiveQuery, int roundsLeft) {
        return synthesize(payload, gatherFor(household, user, effectiveQuery))
                .flatMap(result -> {
                    // Budget exhausted → return best-effort without spending a self-check call (cheap-first).
                    if (roundsLeft <= 1) {
                        return Mono.just(result);
                    }
                    return assessor.assess(originalQuery, result.text())
                            .flatMap(a -> a.sufficient()
                                    ? Mono.just(result)
                                    : reGather(household, user, payload, originalQuery, result, a, roundsLeft));
                });
    }

    /** Under-confident and budget remains: sharpen the focus, fold in the prior draft, run one more round. */
    private Mono<CoordinationResult> reGather(UUID household, UUID user, JsonNode payload,
                                              String originalQuery, CoordinationResult prior,
                                              Assessment assessment, int roundsLeft) {
        String missing = assessment.missing();
        String sharpened = (missing == null || missing.isBlank())
                ? originalQuery
                : originalQuery + " — уточни: " + missing;
        log.debug("coordinator re-gather (roundsLeft={}), missing='{}'", roundsLeft - 1, missing);
        return round(household, user, refinePayload(payload, prior.text(), missing),
                originalQuery, sharpened, roundsLeft - 1);
    }

    /** The next-round payload: the base request plus the prior draft + focus hint so the model refines. */
    private JsonNode refinePayload(JsonNode base, String priorDraft, String missing) {
        ObjectNode refined = base != null && base.isObject()
                ? ((ObjectNode) base).deepCopy()
                : json.createObjectNode();
        refined.put("priorDraft", priorDraft == null ? "" : priorDraft);
        refined.put("refineFocus", missing == null ? "" : missing);
        return refined;
    }

    /**
     * Build the parallel gather map for this request. Two sources: long-term memory recall from the
     * second brain (Slice A) and live specialist {@code brief} answers picked + gathered through the hub
     * (Slice B2). Each step soft-fails to "omitted" inside the {@link Coordinator}.
     */
    Map<String, Mono<JsonNode>> gatherFor(UUID household, UUID user, String query) {
        Map<String, Mono<JsonNode>> gather = new LinkedHashMap<>();
        gather.put("memories", memory.recall(household, user, null, query).map(json::valueToTree));
        gather.put("briefs", specialistBriefs.gather(household, user, query));
        return gather;
    }

    /** The one LLM synthesis over {@code [AGENT.md body] + {payload, context}} on the DEFAULT channel. */
    private Mono<CoordinationResult> synthesize(JsonNode payload, Map<String, Mono<JsonNode>> gather) {
        return coordinator.coordinate(List.of(manifest.body()), payload, gather, LlmChannel.DEFAULT);
    }

    /** Relevance gate for a proactive surface: non-blank and substantial enough to be worth an interruption. */
    public static boolean isWorthSurfacing(String text) {
        return text != null && text.strip().length() >= MIN_SURFACE_CHARS;
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }
}
