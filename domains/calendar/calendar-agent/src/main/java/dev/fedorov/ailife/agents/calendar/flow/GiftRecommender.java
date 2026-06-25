package dev.fedorov.ailife.agents.calendar.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.coordinate.CoordinationResult;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.http.MemoryClient;
import dev.fedorov.ailife.agentruntime.http.NotifierClient;
import dev.fedorov.ailife.agentruntime.http.ProfileClient;
import dev.fedorov.ailife.agentruntime.http.OrchestratorInvokeClient;
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
 *       invoke hub; the person's {@code relationship} (when set) is passed so finance
 *       returns the relationship-tiered rule, falling back to the household "Gifts"
 *       envelope (D3d);</li>
 *   <li><b>memories</b> — long-term recall about the person from memory-service;</li>
 *   <li><b>relations</b> — the person's graph relations (when any exist).</li>
 * </ul>
 * The {@link Coordinator} folds the successful sources into a {@code context} object and
 * asks the LLM to synthesize budget-aware gift ideas from {@code [AGENT.md, SKILL.md]} +
 * {@code {payload(person), context}}. Each gather step soft-fails independently, so a
 * finance outage just drops the budget constraint rather than sinking the suggestion.
 * The single wake produces <b>two outputs</b> (D3e): a short deterministic birthday
 * reminder followed by the synthesized gift ideas, both fanned out to every household
 * member via notifier-service.
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
        gather.put("budget", fetchGiftBudget(household, person));
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
                .flatMap(result -> notifyHousehold(household, person, req, result));
    }

    /**
     * Ask finance for the household's gift budget through the orchestrator hub.
     * When the person carries a {@code relationship} (D3d) it is passed as
     * {@code args.relationship} so finance can return the relationship-tiered
     * rule (e.g. parent → 20000 RUB), falling back to the household "Gifts"
     * envelope when no tier rule is set.
     */
    private Mono<JsonNode> fetchGiftBudget(UUID household, PersonDto person) {
        if (household == null) return Mono.empty();
        JsonNode args = null;
        if (person != null && person.relationship() != null && !person.relationship().isBlank()) {
            args = json.createObjectNode().put("relationship", person.relationship());
        }
        var request = new AgentActionRequest(
                "finance", "get_gift_budget", household, null, "calendar", args);
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

    /**
     * Fan two outputs out of the single {@code gift.recommend} wake (D3e): a short
     * deterministic birthday <b>reminder</b> followed by the LLM-synthesized
     * <b>gift ideas</b>. Each household member receives both, reminder first. The
     * reminder is skipped when no person could be resolved (nobody to name); the
     * gift message is skipped when the synthesis came back empty — so a degraded
     * run still delivers whatever it has.
     */
    private Mono<Void> notifyHousehold(UUID household, PersonDto person,
                                       AgentWakeRequest req, CoordinationResult result) {
        String gifts = result.text();
        String reminder = buildReminder(person, req);
        log.info("gift.recommend coordinated: budget={} reminder={} gifts={} chars",
                result.gathered().has("budget"), reminder != null,
                gifts == null ? 0 : gifts.length());
        if (household == null
                || (reminder == null && (gifts == null || gifts.isBlank()))) {
            return Mono.empty();
        }
        return profile.usersByHousehold(household)
                .flatMap(u -> deliver(u.id(), reminder).then(deliver(u.id(), gifts)))
                .then();
    }

    /** One soft-failed notify; a blank message is a no-op so the chain stays simple. */
    private Mono<Void> deliver(UUID userId, String text) {
        if (text == null || text.isBlank()) return Mono.empty();
        return notifier.notify(userId, text)
                .doOnError(e -> log.warn("notify failed for user={}: {}", userId, e.toString()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    /**
     * The deterministic birthday reminder (D3e). Names the person and, when the
     * wake payload carries a {@code daysUntil} count, how soon it is. Returns
     * {@code null} when there's no person to name — then only the gift ideas go out.
     */
    private String buildReminder(PersonDto person, AgentWakeRequest req) {
        if (person == null || person.displayName() == null || person.displayName().isBlank()) {
            return null;
        }
        String who = person.displayName();
        Integer days = daysUntil(req == null ? null : req.payload());
        if (days != null && days > 0) {
            return "🎂 Напоминание: через " + days + " дн. день рождения у " + who + ".";
        }
        return "🎂 Напоминание: скоро день рождения у " + who + ".";
    }

    /** Optional {@code payload.daysUntil} (lead time the scheduler fired on); absent → null. */
    private static Integer daysUntil(JsonNode payload) {
        if (payload == null) return null;
        JsonNode d = payload.get("daysUntil");
        return (d != null && d.isInt()) ? d.asInt() : null;
    }
}
