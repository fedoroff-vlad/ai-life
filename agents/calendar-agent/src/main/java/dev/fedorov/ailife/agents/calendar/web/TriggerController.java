package dev.fedorov.ailife.agents.calendar.web;

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
 * {@link Skill} via {@link SkillRegistry} and runs it: the system prompt is
 * AGENT.md body + SKILL.md body, the user message is the wake payload (as
 * JSON). The generated text is logged for now — wiring to notifier-service so
 * the user actually receives the greeting is a follow-up PR.
 */
@RestController
@RequestMapping("/agents/calendar/triggers")
public class TriggerController {

    private static final Logger log = LoggerFactory.getLogger(TriggerController.class);

    private final LlmClient llm;
    private final AgentManifest manifest;
    private final SkillRegistry skills;

    public TriggerController(LlmClient llm, AgentManifest manifest, SkillRegistry skills) {
        this.llm = llm;
        this.manifest = manifest;
        this.skills = skills;
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
                .doOnNext(resp -> log.info("trigger kind={} skill={} produced ({} chars): {}",
                        kind, skill.name(), resp.content() == null ? 0 : resp.content().length(),
                        resp.content()))
                .then(Mono.just(ResponseEntity.<Void>accepted().build()));
    }
}
