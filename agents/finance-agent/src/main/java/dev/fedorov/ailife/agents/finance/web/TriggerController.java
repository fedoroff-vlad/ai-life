package dev.fedorov.ailife.agents.finance.web;

import dev.fedorov.ailife.agents.finance.skill.Skill;
import dev.fedorov.ailife.agents.finance.skill.SkillRegistry;
import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Hit by orchestrator when scheduler-service wakes this agent. PR22 ships the
 * skeleton only — no skills bound yet, so every kind 404s. First skill
 * (budget-alerts or transaction-categorizer) will wire LLM call + notifier
 * fan-out by mirroring calendar-agent's PR9c2/PR10 flow.
 */
@RestController
@RequestMapping("/agents/finance/triggers")
public class TriggerController {

    private static final Logger log = LoggerFactory.getLogger(TriggerController.class);

    private final SkillRegistry skills;

    public TriggerController(SkillRegistry skills) {
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
        // First skill PR replaces this with the real LLM + notifier flow.
        log.info("trigger kind={} matched skill={} but executor is not implemented yet", kind, skill.name());
        return Mono.just(ResponseEntity.<Void>accepted().build());
    }
}
