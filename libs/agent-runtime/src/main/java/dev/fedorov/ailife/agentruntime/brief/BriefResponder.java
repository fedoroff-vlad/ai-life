package dev.fedorov.ailife.agentruntime.brief;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.http.MemoryClient;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The reusable <b>{@code brief}</b> read-action (#290, Slice B) — the generic "answer a focused
 * sub-question from your domain, read-only" primitive that lets the coordinator gather <i>live</i>
 * specialist input through the hub, not just long-term memory. Any agent exposes it by registering
 * {@code register("brief", briefResponder::answer)} on its {@code ActionController}; the shared
 * envelope + error wrapping come from {@code AgentActionController}.
 *
 * <p>Shape reuses the {@link Coordinator}: gather (the agent's own second-brain recall for the
 * question, plus any domain context the caller adds) → one <b>{@link LlmChannel#FAST}</b> answer over
 * {@code [AGENT.md persona, the read-only brief instruction]}. FAST keeps a sub-query cheap — the
 * coordinator's final cross-domain synthesis is the DEFAULT-channel call; the specialist briefs
 * feeding it are the cheap leg (cheap-first).
 *
 * <p><b>Read-only by contract:</b> the instruction forbids taking any action, and the handler only
 * recalls + answers — no writes, no outbound effects. The result is
 * {@code {agent, answer, llmModel?}}; a missing question is a structured {@code ok=false}, never a throw.
 */
public class BriefResponder {

    private static final String QUESTION_ARG = "question";

    /** The read-only framing layered on top of the agent's own AGENT.md persona. */
    private static final String BRIEF_INSTRUCTION = """
            You are being asked a focused sub-question by the coordinator agent, on the owner's behalf.
            Answer ONLY from your own domain and the provided context. This is READ-ONLY: never take an
            action, make a change, or promise to do anything — just report what you know. Be concise and
            factual, and ground every claim in the provided context. If you have nothing relevant, say so
            in one short sentence.""";

    private final Coordinator coordinator;
    private final MemoryClient memory;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public BriefResponder(Coordinator coordinator, MemoryClient memory,
                          AgentManifest manifest, ObjectMapper json) {
        this.coordinator = coordinator;
        this.memory = memory;
        this.manifest = manifest;
        this.json = json;
    }

    /** Answer the sub-question in {@code args.question}, grounded in the agent's second-brain recall. */
    public Mono<AgentActionResult> answer(AgentActionRequest request) {
        return answer(request, Map.of());
    }

    /**
     * Answer the sub-question, folding {@code extraGather} (the agent's own live domain reads, e.g. a
     * spend snapshot or today's agenda) into the context alongside the second-brain recall. Each gather
     * step soft-fails independently inside the {@link Coordinator}.
     */
    public Mono<AgentActionResult> answer(AgentActionRequest request,
                                          Map<String, Mono<JsonNode>> extraGather) {
        String question = questionArg(request);
        if (question == null) {
            return Mono.just(AgentActionResult.error("brief requires args.question"));
        }
        UUID household = request.householdId();
        UUID user = request.userId();

        Map<String, Mono<JsonNode>> gather = new LinkedHashMap<>();
        gather.put("memories", memory.recall(household, user, null, question).map(json::valueToTree));
        if (extraGather != null) {
            gather.putAll(extraGather);
        }

        ObjectNode payload = json.createObjectNode();
        payload.put("question", question);

        return coordinator.coordinate(List.of(manifest.body(), BRIEF_INSTRUCTION), payload, gather, LlmChannel.FAST)
                .map(r -> {
                    ObjectNode result = json.createObjectNode();
                    result.put("agent", manifest.name());
                    result.put("answer", r.text() == null ? "" : r.text());
                    if (r.llmModel() != null) {
                        result.put("llmModel", r.llmModel());
                    }
                    return AgentActionResult.ok(result);
                });
    }

    private String questionArg(AgentActionRequest request) {
        JsonNode args = request == null ? null : request.args();
        if (args == null) {
            return null;
        }
        JsonNode q = args.get(QUESTION_ARG);
        if (q == null || q.isNull()) {
            return null;
        }
        String s = q.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
