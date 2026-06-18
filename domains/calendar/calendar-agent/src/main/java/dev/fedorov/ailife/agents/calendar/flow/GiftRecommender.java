package dev.fedorov.ailife.agents.calendar.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.coordinate.CoordinationResult;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.http.MemoryClient;
import dev.fedorov.ailife.agentruntime.http.NotifierClient;
import dev.fedorov.ailife.agentruntime.http.ProfileClient;
import dev.fedorov.ailife.agents.calendar.http.OrchestratorInvokeClient;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.profile.PersonDto;
import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The first real {@link Coordinator} flow (Stage 4 / Track D, D2c): a budget-aware
 * gift recommendation. On a {@code gift.recommend} wake it gathers, in parallel:
 * <ul>
 *   <li><b>budget</b> — finance-agent's {@code get_gift_budget} via the orchestrator
 *       invoke hub (the household's "Gifts" envelope);</li>
 *   <li><b>memories</b> — long-term recall about the person from memory-service;</li>
 *   <li><b>relations</b> — the person's graph relations (when any exist).</li>
 * </ul>
 * The {@link Coordinator} folds the successful sources into a {@code context} object and
 * asks the LLM to synthesize budget-aware gift ideas from {@code [AGENT.md, SKILL.md]} +
 * {@code {payload(person), context}}. Each gather step soft-fails independently, so a
 * finance outage just drops the budget constraint rather than sinking the suggestion.
 * The synthesized text is fanned out to every household member via notifier-service —
 * same delivery path as the generic trigger flow.
 */
@Component
public class GiftRecommender {

    private static final Logger log = LoggerFactory.getLogger(GiftRecommender.class);

    private final Coordinator coordinator;
    private final OrchestratorInvokeClient orchestrator;
    private final MemoryClient memory;
    private final ProfileClient profile;
    private final NotifierClient notifier;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public GiftRecommender(Coordinator coordinator,
                           OrchestratorInvokeClient orchestrator,
                           MemoryClient memory,
                           ProfileClient profile,
                           NotifierClient notifier,
                           AgentManifest manifest,
                           ObjectMapper json) {
        this.coordinator = coordinator;
        this.orchestrator = orchestrator;
        this.memory = memory;
        this.profile = profile;
        this.notifier = notifier;
        this.manifest = manifest;
        this.json = json;
    }

    public Mono<Void> recommend(Skill skill, AgentWakeRequest req, PersonDto person) {
        UUID household = req.householdId();
        UUID personId = person == null ? null : person.id();

        Map<String, Mono<JsonNode>> gather = new LinkedHashMap<>();
        gather.put("budget", fetchGiftBudget(household));
        gather.put("memories", fetchMemories(household, personId, person));
        gather.put("relations", fetchRelations(household, personId));

        ObjectNode payload = json.createObjectNode();
        payload.set("payload", req.payload() == null ? json.createObjectNode() : req.payload());
        if (person != null) {
            payload.set("person", json.valueToTree(person));
        }

        return coordinator.coordinate(
                        java.util.List.of(manifest.body(), skill.body()),
                        payload,
                        gather,
                        LlmChannel.DEFAULT)
                .flatMap(result -> notifyHousehold(household, result));
    }

    /** Ask finance for the household's gift budget through the orchestrator hub. */
    private Mono<JsonNode> fetchGiftBudget(UUID household) {
        if (household == null) return Mono.empty();
        var request = new AgentActionRequest(
                "finance", "get_gift_budget", household, null, "calendar", null);
        return orchestrator.invoke(request)
                .filter(AgentActionResult::ok)
                .map(AgentActionResult::result);
    }

    /** Long-term recall about the person; empty list → omitted by the coordinator. */
    private Mono<JsonNode> fetchMemories(UUID household, UUID personId, PersonDto person) {
        if (household == null) return Mono.empty();
        String query = (person != null && person.displayName() != null
                && !person.displayName().isBlank())
                ? "gift ideas for " + person.displayName()
                : "gift ideas in household " + household;
        return memory.recall(household, null, personId, query)
                .map(hits -> (JsonNode) json.valueToTree(hits));
    }

    /** Graph relations; only emitted when there's at least one edge. */
    private Mono<JsonNode> fetchRelations(UUID household, UUID personId) {
        if (household == null || personId == null) return Mono.empty();
        return memory.personRelations(household, personId)
                .flatMap(rel -> (rel.outgoing().isEmpty() && rel.incoming().isEmpty())
                        ? Mono.empty()
                        : Mono.just((JsonNode) json.valueToTree(rel)));
    }

    private Mono<Void> notifyHousehold(UUID household, CoordinationResult result) {
        String text = result.text();
        log.info("gift.recommend coordinated: budget={} produced {} chars",
                result.gathered().has("budget"), text == null ? 0 : text.length());
        if (text == null || text.isBlank() || household == null) {
            return Mono.empty();
        }
        return profile.usersByHousehold(household)
                .flatMap(u -> notifier.notify(u.id(), text)
                        .doOnError(e -> log.warn("notify failed for user={}: {}", u.id(), e.toString()))
                        .onErrorResume(e -> Mono.empty()))
                .then();
    }
}
