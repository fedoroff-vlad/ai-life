package dev.fedorov.ailife.agents.coordinator.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.coordinate.CoordinationResult;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.http.MemoryClient;
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
 * <p><b>Loop-ready seam:</b> {@link #gatherFor} builds the gather map and {@link #synthesize} runs the
 * one LLM call; {@link #run} chains them. A later bounded {@code plan → gather → maybe-gather-again}
 * loop wraps {@link #run} — the two entry points and the controllers stay unchanged.
 *
 * <p><b>Model-agnostic:</b> the synthesis is one {@link LlmChannel#DEFAULT} call whose quality scales
 * with whatever provider llm-gateway is pointed at (mock / local / API) — no code assumes model
 * strength, and per-source soft-fail is inherited from the {@link Coordinator}.
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
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public MultiDomainCoordinator(Coordinator coordinator, MemoryClient memory,
                                  AgentManifest manifest, ObjectMapper json) {
        this.coordinator = coordinator;
        this.memory = memory;
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

    /** The gather→synthesize core both entries share — the seam a future multi-step loop wraps. */
    private Mono<CoordinationResult> run(UUID household, UUID user, JsonNode payload, String query) {
        return synthesize(payload, gatherFor(household, user, query));
    }

    /**
     * Build the parallel gather map for this request. Slice A = long-term memory recall from the second
     * brain; each step soft-fails to "omitted" inside the {@link Coordinator}. Slice B adds live
     * cross-agent answers here.
     */
    Map<String, Mono<JsonNode>> gatherFor(UUID household, UUID user, String query) {
        Map<String, Mono<JsonNode>> gather = new LinkedHashMap<>();
        gather.put("memories", memory.recall(household, user, null, query).map(json::valueToTree));
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
