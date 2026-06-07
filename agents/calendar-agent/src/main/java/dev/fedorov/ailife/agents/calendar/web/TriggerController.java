package dev.fedorov.ailife.agents.calendar.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.http.MemoryClient;
import dev.fedorov.ailife.agentruntime.http.NotifierClient;
import dev.fedorov.ailife.agentruntime.http.ProfileClient;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.calendar.system.SystemTriggerHandler;
import dev.fedorov.ailife.agents.calendar.system.SystemTriggerRegistry;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.memory.PersonRelationsResponse;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.contracts.profile.PersonDto;
import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Hit by orchestrator when scheduler-service wakes this agent for a {@code kind}
 * declared in {@link AgentManifest#triggers()}. Resolves the trigger to a
 * {@link Skill}, runs it (LLM with AGENT.md + SKILL.md bodies as layered system
 * prompts and the wake payload as the user message), then fans the generated
 * text out to every user in the wake's household via notifier-service.
 *
 * <p>Notifier failures per-user don't block the others — they're logged and the
 * overall response is still 202. The schedule advances on the scheduler side
 * regardless, since the wake itself succeeded.
 */
@RestController
@RequestMapping("/agents/calendar/triggers")
public class TriggerController {

    private static final Logger log = LoggerFactory.getLogger(TriggerController.class);

    private final LlmClient llm;
    private final AgentManifest manifest;
    private final SkillRegistry skills;
    private final SystemTriggerRegistry systemTriggers;
    private final ProfileClient profile;
    private final NotifierClient notifier;
    private final MemoryClient memory;
    private final ObjectMapper json;

    public TriggerController(LlmClient llm,
                             AgentManifest manifest,
                             SkillRegistry skills,
                             SystemTriggerRegistry systemTriggers,
                             ProfileClient profile,
                             NotifierClient notifier,
                             MemoryClient memory,
                             ObjectMapper json) {
        this.llm = llm;
        this.manifest = manifest;
        this.skills = skills;
        this.systemTriggers = systemTriggers;
        this.profile = profile;
        this.notifier = notifier;
        this.memory = memory;
        this.json = json;
    }

    @PostMapping("/{kind}")
    public Mono<ResponseEntity<Void>> trigger(@PathVariable String kind,
                                              @RequestBody AgentWakeRequest req) {
        // System triggers (cron-driven, no NL) bypass the LLM entirely. Checked
        // before SkillRegistry so a kind cannot accidentally be both — system wins.
        SystemTriggerHandler system = systemTriggers.forKind(kind).orElse(null);
        if (system != null) {
            return system.handle(req)
                    .then(Mono.just(ResponseEntity.<Void>accepted().build()));
        }

        Skill skill = skills.forTrigger(kind).orElse(null);
        if (skill == null) {
            log.warn("no skill bound to trigger kind={} (schedule={})", kind, req.scheduleId());
            return Mono.just(ResponseEntity.notFound().build());
        }

        // Wrap in Optional so a missing person still emits a value through the
        // chain — switchIfEmpty on a downstream Mono<Void> would mis-fire here
        // (Mono<Void> always completes empty).
        return resolvePerson(req.payload())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(personOpt -> runSkill(kind, skill, req, personOpt.orElse(null)))
                .then(Mono.just(ResponseEntity.<Void>accepted().build()));
    }

    /**
     * If the wake payload carries a {@code personId}, fetch the row from
     * profile-service so the skill sees real fields. Failures are swallowed —
     * the skill prompts handle the no-person case explicitly.
     */
    private Mono<PersonDto> resolvePerson(JsonNode payload) {
        if (payload == null) return Mono.empty();
        JsonNode pid = payload.get("personId");
        if (pid == null || pid.isNull() || pid.asText().isBlank()) return Mono.empty();
        UUID personId;
        try {
            personId = UUID.fromString(pid.asText());
        } catch (IllegalArgumentException e) {
            log.warn("payload.personId not a UUID: {}", pid.asText());
            return Mono.empty();
        }
        return profile.personById(personId)
                .doOnError(e -> log.warn("person lookup failed for {}: {}", personId, e.toString()))
                .onErrorResume(e -> Mono.empty());
    }

    private Mono<Void> runSkill(String kind, Skill skill, AgentWakeRequest req, PersonDto person) {
        // Cross-agent chain: enrich the user message with long-term memory + relations
        // BEFORE the LLM call. Both calls are soft-fail in MemoryClient — empty
        // results just mean the skill runs without that context (existing behaviour).
        UUID personId = person == null ? null : person.id();
        String recallQuery = buildRecallQuery(kind, person, req);

        Mono<List<RecallMemoryHit>> recallMono = memory.recall(
                req.householdId(), null, personId, recallQuery);
        Mono<PersonRelationsResponse> relationsMono = (personId == null)
                ? Mono.just(new PersonRelationsResponse(null, List.of(), List.of()))
                : memory.personRelations(req.householdId(), personId);

        return Mono.zip(recallMono, relationsMono).flatMap(tuple -> {
            List<RecallMemoryHit> memories = tuple.getT1();
            PersonRelationsResponse relations = tuple.getT2();

            ObjectNode userMsg = json.createObjectNode();
            userMsg.set("payload", req.payload() == null ? json.createObjectNode() : req.payload());
            if (person != null) {
                userMsg.set("person", json.valueToTree(person));
            }
            if (!memories.isEmpty()) {
                userMsg.set("memories", json.valueToTree(memories));
            }
            if (personId != null
                    && (!relations.outgoing().isEmpty() || !relations.incoming().isEmpty())) {
                userMsg.set("relations", json.valueToTree(relations));
            }

            var chat = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                    LlmMessage.system(manifest.body()),
                    LlmMessage.system(skill.body()),
                    LlmMessage.user(userMsg.toString())));

            return llm.chat(chat).flatMap(resp -> {
                String text = resp.content();
                log.info("trigger kind={} skill={} person={} memories={} relations={} produced {} chars",
                        kind, skill.name(),
                        person == null ? "<none>" : person.id(),
                        memories.size(),
                        relations.outgoing().size() + relations.incoming().size(),
                        text == null ? 0 : text.length());
                if (text == null || text.isBlank() || req.householdId() == null) {
                    return Mono.empty();
                }
                return profile.usersByHousehold(req.householdId())
                        .flatMap(u -> notifier.notify(u.id(), text)
                                .doOnError(e -> log.warn(
                                        "notify failed for user={} kind={}: {}",
                                        u.id(), kind, e.toString()))
                                .onErrorResume(e -> Mono.empty()))
                        .then();
            });
        });
    }

    /**
     * Build the natural-language query memory-service will embed for recall.
     * Anchored on the person's display name when available; otherwise we use
     * the trigger kind so the recall is at least topic-aware.
     */
    private static String buildRecallQuery(String kind, PersonDto person, AgentWakeRequest req) {
        if (person != null && person.displayName() != null && !person.displayName().isBlank()) {
            return kind + " for " + person.displayName();
        }
        if (req.householdId() != null) {
            return kind + " in household " + req.householdId();
        }
        return kind;
    }
}
