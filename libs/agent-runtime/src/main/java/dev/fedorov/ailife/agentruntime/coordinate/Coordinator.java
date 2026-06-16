package dev.fedorov.ailife.agentruntime.coordinate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reusable <b>gather → synthesize</b> coordinator — the agent-led multi-source pattern
 * behind proactive/reactive flows (e.g. birthday → gather memory + finance budget →
 * propose a gift). An agent supplies named async <i>gather steps</i> (a memory recall, an
 * inter-agent invoke via the orchestrator, a tool call); the coordinator runs them in
 * parallel, assembles their results into one {@code context} object, and asks the LLM to
 * synthesize a single answer from the layered system prompts + payload + gathered context.
 *
 * <p><b>Per-step soft-fail:</b> a gather step that errors or returns nothing is simply
 * omitted from the context (logged) — one slow/broken source never sinks the whole
 * synthesis. This generalises the soft-fail memory-recall enrichment that calendar-agent
 * and finance-agent already do by hand.
 *
 * <p>Coordination is <b>agent-led</b> by design (architecture.md §routing doctrine): the
 * agent owns the flow and reaches other specialists through the hub
 * ({@code /v1/agents/invoke}); the orchestrator stays a thin router, not a planner.
 */
public class Coordinator {

    private static final Logger log = LoggerFactory.getLogger(Coordinator.class);

    private final LlmClient llm;
    private final ObjectMapper json;

    public Coordinator(LlmClient llm, ObjectMapper json) {
        this.llm = llm;
        this.json = json;
    }

    /**
     * Run the gather steps in parallel, then synthesize one answer.
     *
     * @param systemPrompts layered system prompts (e.g. {@code [AGENT.md body, skill body]});
     *                      null/blank entries are skipped
     * @param payload       the trigger/intent payload (may be null)
     * @param gather        name → async source; each soft-fails to "omitted"
     * @param channel       LLM channel ({@code default}/{@code fast})
     */
    public Mono<CoordinationResult> coordinate(List<String> systemPrompts,
                                               JsonNode payload,
                                               Map<String, Mono<JsonNode>> gather,
                                               LlmChannel channel) {
        return gatherContext(gather).flatMap(context -> {
            ObjectNode userMsg = json.createObjectNode();
            userMsg.set("payload", payload == null ? json.createObjectNode() : payload);
            userMsg.set("context", context);

            List<LlmMessage> messages = new ArrayList<>();
            if (systemPrompts != null) {
                for (String p : systemPrompts) {
                    if (p != null && !p.isBlank()) {
                        messages.add(LlmMessage.system(p));
                    }
                }
            }
            messages.add(LlmMessage.user(userMsg.toString()));

            return llm.chat(LlmChatRequest.of(channel, messages))
                    .map(resp -> new CoordinationResult(resp.content(), context, resp.model()));
        });
    }

    /**
     * Resolve every gather step in parallel and fold the successful, non-empty ones into a
     * single object. Results are collected before the object is built, so the shared
     * {@link ObjectNode} is only ever touched on one thread.
     */
    private Mono<ObjectNode> gatherContext(Map<String, Mono<JsonNode>> gather) {
        if (gather == null || gather.isEmpty()) {
            return Mono.just(json.createObjectNode());
        }
        return Flux.fromIterable(gather.entrySet())
                .flatMap(e -> resolveStep(e.getKey(), e.getValue()))
                .collectList()
                .map(entries -> {
                    ObjectNode context = json.createObjectNode();
                    for (Map.Entry<String, JsonNode> entry : entries) {
                        context.set(entry.getKey(), entry.getValue());
                    }
                    return context;
                });
    }

    private Mono<Map.Entry<String, JsonNode>> resolveStep(String name, Mono<JsonNode> source) {
        if (source == null) {
            return Mono.empty();
        }
        return source
                .filter(Coordinator::isPresent)
                .map(v -> (Map.Entry<String, JsonNode>) new AbstractMap.SimpleEntry<>(name, v))
                .onErrorResume(err -> {
                    log.warn("gather step '{}' failed: {}", name, err.toString());
                    return Mono.empty();
                });
    }

    /** Treat null / JSON null / missing / empty object / empty array as "nothing gathered". */
    private static boolean isPresent(JsonNode v) {
        return v != null && !v.isNull() && !v.isMissingNode()
                && !(v.isObject() && v.isEmpty())
                && !(v.isArray() && v.isEmpty());
    }
}
