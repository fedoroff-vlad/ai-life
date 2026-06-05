package dev.fedorov.ailife.agents.calendar.web;

import dev.fedorov.ailife.agents.calendar.http.NotifierClient;
import dev.fedorov.ailife.agents.calendar.http.ProfileClient;
import dev.fedorov.ailife.agents.calendar.skill.Skill;
import dev.fedorov.ailife.agents.calendar.skill.SkillRegistry;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
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
    private final ProfileClient profile;
    private final NotifierClient notifier;

    public TriggerController(LlmClient llm,
                             AgentManifest manifest,
                             SkillRegistry skills,
                             ProfileClient profile,
                             NotifierClient notifier) {
        this.llm = llm;
        this.manifest = manifest;
        this.skills = skills;
        this.profile = profile;
        this.notifier = notifier;
    }

    @PostMapping("/{kind}")
    public Mono<ResponseEntity<Void>> trigger(@PathVariable String kind,
                                              @RequestBody AgentWakeRequest req) {
        Skill skill = skills.forTrigger(kind).orElse(null);
        if (skill == null) {
            log.warn("no skill bound to trigger kind={} (schedule={})", kind, req.scheduleId());
            return Mono.just(ResponseEntity.notFound().build());
        }

        String userPayload = req.payload() == null ? "{}" : req.payload().toString();
        var chat = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(skill.body()),
                LlmMessage.user(userPayload)));

        return llm.chat(chat)
                .flatMap(resp -> {
                    String text = resp.content();
                    log.info("trigger kind={} skill={} produced {} chars",
                            kind, skill.name(), text == null ? 0 : text.length());
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
                })
                .then(Mono.just(ResponseEntity.<Void>accepted().build()));
    }
}
