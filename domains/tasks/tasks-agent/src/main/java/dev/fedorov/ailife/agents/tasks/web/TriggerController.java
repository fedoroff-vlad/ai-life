package dev.fedorov.ailife.agents.tasks.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.http.NotifierClient;
import dev.fedorov.ailife.agentruntime.http.ProfileClient;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.tasks.http.TaskReviewClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import dev.fedorov.ailife.contracts.tasks.WeeklyReviewResult;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Hit by orchestrator when scheduler-service wakes this agent for a {@code kind} declared in
 * {@link AgentManifest#triggers()}. Resolves the trigger to a {@link Skill}, enriches the wake
 * payload (for {@code weekly.review}, via mcp-tasks' {@code /internal/review} aggregate), runs the
 * skill (LLM with AGENT.md + SKILL.md bodies as layered system prompts), and fans the generated
 * text out to every household member via notifier-service. Mirrors finance/calendar's
 * TriggerController.
 *
 * <p>The {@code SKIP} sentinel the skill emits on a clean week short-circuits before profile/
 * notifier are touched. A failed enrichment returns 503 so scheduler-service retries next tick.
 */
@RestController
@RequestMapping("/agents/tasks/triggers")
public class TriggerController {

    private static final Logger log = LoggerFactory.getLogger(TriggerController.class);

    static final String SKIP_SENTINEL = "SKIP";
    static final String WEEKLY_REVIEW_KIND = "weekly.review";

    private final LlmClient llm;
    private final AgentManifest manifest;
    private final SkillRegistry skills;
    private final ProfileClient profile;
    private final NotifierClient notifier;
    private final TaskReviewClient review;
    private final ObjectMapper json;

    public TriggerController(LlmClient llm,
                            AgentManifest manifest,
                            SkillRegistry skills,
                            ProfileClient profile,
                            NotifierClient notifier,
                            TaskReviewClient review,
                            ObjectMapper json) {
        this.llm = llm;
        this.manifest = manifest;
        this.skills = skills;
        this.profile = profile;
        this.notifier = notifier;
        this.review = review;
        this.json = json;
    }

    @PostMapping("/{kind}")
    public Mono<ResponseEntity<Void>> trigger(@PathVariable String kind,
                                              @RequestBody AgentWakeRequest req) {
        Skill skill = skills.forTrigger(kind).orElse(null);
        if (skill == null) {
            log.warn("no skill bound to trigger kind={} (schedule={})", kind, req.scheduleId());
            return Mono.just(ResponseEntity.notFound().build());
        }
        return enrichIfNeeded(kind, req)
                .flatMap(enriched -> runSkill(kind, skill, enriched)
                        .then(Mono.fromCallable(() -> ResponseEntity.accepted().<Void>build())))
                .onErrorResume(EnrichmentFailedException.class, e -> {
                    log.warn("trigger enrichment failed (kind={} schedule={}): {} — 503 so scheduler retries",
                            kind, req.scheduleId(), e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build());
                });
    }

    /**
     * For a scheduler-driven {@code weekly.review} wake, fetch the live aggregate from mcp-tasks
     * and rewrite the payload into the shape the skill is trained on. Already-enriched payloads
     * (manual callers carrying {@code inboxCount}) pass through unchanged.
     */
    private Mono<AgentWakeRequest> enrichIfNeeded(String kind, AgentWakeRequest req) {
        if (!WEEKLY_REVIEW_KIND.equals(kind) || req.householdId() == null) {
            return Mono.just(req);
        }
        if (req.payload() != null && req.payload().hasNonNull("inboxCount")) {
            return Mono.just(req); // already enriched
        }
        return review.fetch(req.householdId())
                .map(result -> withPayload(req, reviewPayload(result)))
                .onErrorMap(EnrichmentFailedException::wrap);
    }

    private JsonNode reviewPayload(WeeklyReviewResult result) {
        return json.valueToTree(result);
    }

    private Mono<Void> runSkill(String kind, Skill skill, AgentWakeRequest req) {
        ObjectNode userMsg = json.createObjectNode();
        userMsg.set("payload", req.payload() == null ? json.createObjectNode() : req.payload());

        LlmChatRequest chat = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(skill.body()),
                LlmMessage.user(userMsg.toString())));

        return llm.chat(chat).flatMap(resp -> {
            String text = resp.content();
            if (text != null && SKIP_SENTINEL.equalsIgnoreCase(text.trim())) {
                log.info("trigger kind={} skill={} schedule={} produced SKIP — no notification",
                        kind, skill.name(), req.scheduleId());
                return Mono.empty();
            }
            if (text == null || text.isBlank() || req.householdId() == null) {
                return Mono.empty();
            }
            return profile.usersByHousehold(req.householdId())
                    .flatMap(u -> notifier.notify(u.id(), text)
                            .doOnError(e -> log.warn("notify failed for user={} kind={}: {}",
                                    u.id(), kind, e.toString()))
                            .onErrorResume(e -> Mono.empty()))
                    .then();
        });
    }

    private static AgentWakeRequest withPayload(AgentWakeRequest req, JsonNode payload) {
        return new AgentWakeRequest(req.scheduleId(), req.householdId(), req.agent(),
                req.kind(), payload);
    }

    private static final class EnrichmentFailedException extends RuntimeException {
        EnrichmentFailedException(Throwable cause) { super(cause); }

        static Throwable wrap(Throwable e) {
            return e instanceof EnrichmentFailedException ? e : new EnrichmentFailedException(e);
        }
    }
}
