package dev.fedorov.ailife.agents.coordinator.flow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;
import dev.fedorov.ailife.agentruntime.http.OrchestratorInvokeClient;
import dev.fedorov.ailife.agents.coordinator.config.CoordinatorAgentProperties;
import dev.fedorov.ailife.agents.coordinator.config.CoordinatorAgentProperties.Specialist;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The <b>live specialist-gather leg</b> of the coordinator (#290, Slice B2). A cross-cutting request
 * doesn't only draw on long-term memory — it can also fold in <i>live</i> answers from domain
 * specialists. This turns "pick relevant specialists → ask each a focused sub-question → collect their
 * answers" into ONE gather step {@link MultiDomainCoordinator} plugs into its parallel gather map.
 *
 * <p>Two stages, both soft-failing:
 * <ol>
 *   <li><b>Plan</b> — one {@link LlmChannel#FAST} call reads the configured roster (each specialist's
 *       {@code name} + one-line {@code expertise}) and the request, and returns the subset of
 *       specialists whose domain bears on it (precision over volume — the coordinator does not fan out
 *       to everyone). A planning failure downgrades to "no specialists", never an error.</li>
 *   <li><b>Gather</b> — each chosen specialist's read-only {@code brief} action is invoked in parallel
 *       through the orchestrator hub (agents never call each other directly). A specialist that errors,
 *       times out, answers {@code ok=false}, or has nothing is simply omitted.</li>
 * </ol>
 *
 * <p>The whole step resolves to a {@code {specialistName: answerText, …}} object folded into the
 * coordinator's {@code context.briefs}; an empty result (no specialists picked / all soft-failed) is
 * {@link Mono#empty() empty}, which {@code Coordinator} drops from the context. The specialist briefs
 * are the cheap FAST leg feeding the coordinator's one DEFAULT-channel cross-domain synthesis.
 */
@Component
public class SpecialistBriefs {

    private static final Logger log = LoggerFactory.getLogger(SpecialistBriefs.class);

    /** A {@code brief} fronts a FAST synthesis on the specialist side, so the passthrough default is too tight. */
    private static final Duration BRIEF_TIMEOUT = Duration.ofSeconds(20);

    private static final String BRIEF_ACTION = "brief";
    private static final String QUESTION_ARG = "question";
    private static final String ANSWER_FIELD = "answer";

    /** Stable substring the test harness keys on to recognise a planning turn; keep in sync with the prompt. */
    public static final String PLANNER_MARKER = "routing step of a cross-cutting assistant";

    private static final String PLANNER_SYSTEM = """
            You are the %s. You are given the owner's request and a roster of domain specialists, each
            with a one-line expertise. Decide which specialists — if any — could contribute useful,
            domain-specific facts to the answer. Pick ONLY specialists whose domain clearly bears on the
            request, and prefer fewer over more. Reply with a JSON array of the chosen specialist names
            exactly as spelled in the roster (e.g. ["finance"]), or [] if none is relevant. Output ONLY
            the JSON array — no prose, no code fence.""".formatted(PLANNER_MARKER);

    private final OrchestratorInvokeClient hub;
    private final LlmClient llm;
    private final CoordinatorAgentProperties props;
    private final ObjectMapper json;

    public SpecialistBriefs(OrchestratorInvokeClient hub, LlmClient llm,
                            CoordinatorAgentProperties props, ObjectMapper json) {
        this.hub = hub;
        this.llm = llm;
        this.props = props;
        this.json = json;
    }

    /**
     * One gather step: plan the relevant specialists, invoke each one's {@code brief} through the hub in
     * parallel, and fold the answers into a {@code {name: answer}} object. Empty (nothing to gather) →
     * {@link Mono#empty()}, so the coordinator's context stays memory-only.
     */
    public Mono<JsonNode> gather(UUID household, UUID user, String query) {
        List<Specialist> roster = props.getSpecialists();
        if (roster == null || roster.isEmpty() || query == null || query.isBlank()) {
            return Mono.empty();
        }
        return pick(query, roster)
                .flatMapMany(Flux::fromIterable)
                .flatMap(name -> briefOne(household, user, query, name))
                .collectList()
                .flatMap(entries -> {
                    if (entries.isEmpty()) {
                        return Mono.empty();
                    }
                    ObjectNode node = json.createObjectNode();
                    for (Map.Entry<String, JsonNode> e : entries) {
                        node.set(e.getKey(), e.getValue());
                    }
                    return Mono.just(node);
                });
    }

    /** FAST planning call → the roster names the LLM judged relevant; any failure downgrades to none. */
    private Mono<List<String>> pick(String query, List<Specialist> roster) {
        // Canonical spelling per lower-cased name, so we can accept case-drift in the LLM's echo while
        // returning the exact roster name the hub routes on.
        Map<String, String> canonical = new LinkedHashMap<>();
        StringBuilder rosterText = new StringBuilder();
        for (Specialist s : roster) {
            if (s.getName() == null || s.getName().isBlank()) {
                continue;
            }
            canonical.put(s.getName().toLowerCase(), s.getName());
            rosterText.append("- ").append(s.getName()).append(": ")
                    .append(s.getExpertise() == null ? "" : s.getExpertise()).append('\n');
        }
        if (canonical.isEmpty()) {
            return Mono.just(List.of());
        }
        List<LlmMessage> messages = List.of(
                LlmMessage.system(PLANNER_SYSTEM),
                LlmMessage.user("Specialists:\n" + rosterText + "\nRequest: " + query));
        return llm.chat(LlmChatRequest.of(LlmChannel.FAST, messages, 0.0))
                .map(resp -> parseNames(resp.content(), canonical))
                .onErrorResume(e -> {
                    log.warn("specialist planning failed, gathering no briefs: {}", e.toString());
                    return Mono.just(List.of());
                });
    }

    /** Parse the planner's JSON array, keep only known roster names (canonical spelling), dedupe in order. */
    private List<String> parseNames(String content, Map<String, String> canonical) {
        if (content == null) {
            return List.of();
        }
        int open = content.indexOf('[');
        int close = content.lastIndexOf(']');
        if (open < 0 || close <= open) {
            return List.of();
        }
        List<String> picked = new ArrayList<>();
        try {
            JsonNode arr = json.readTree(content.substring(open, close + 1));
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    String name = canonical.get(n.asText("").trim().toLowerCase());
                    if (name != null && !picked.contains(name)) {
                        picked.add(name);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("could not parse planner reply '{}': {}", content, e.toString());
            return List.of();
        }
        return picked;
    }

    /** Invoke one specialist's read-only {@code brief} via the hub; any soft-failure → omitted. */
    private Mono<Map.Entry<String, JsonNode>> briefOne(UUID household, UUID user, String query, String name) {
        ObjectNode args = json.createObjectNode();
        args.put(QUESTION_ARG, query);
        AgentActionRequest req = new AgentActionRequest(
                name, BRIEF_ACTION, household, user, "coordinator", args);
        return hub.invoke(req, BRIEF_TIMEOUT)
                .flatMap(result -> {
                    String answer = answerOf(result);
                    if (answer == null) {
                        return Mono.<Map.Entry<String, JsonNode>>empty();
                    }
                    Map.Entry<String, JsonNode> entry =
                            new AbstractMap.SimpleEntry<>(name, StringNode.valueOf(answer));
                    return Mono.just(entry);
                })
                .onErrorResume(e -> {
                    log.warn("specialist brief '{}' failed: {}", name, e.toString());
                    return Mono.empty();
                });
    }

    private static String answerOf(AgentActionResult result) {
        if (result == null || !result.ok() || result.result() == null) {
            return null;
        }
        JsonNode answer = result.result().get(ANSWER_FIELD);
        if (answer == null || answer.isNull()) {
            return null;
        }
        String text = answer.asText("").strip();
        return text.isEmpty() ? null : text;
    }
}
