package dev.fedorov.ailife.agents.tasks.web;

import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
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
 * Hit by orchestrator when scheduler-service wakes this agent for a trigger {@code kind}. Skeleton
 * slice: no skills ship yet, so the registry is empty and every kind 404s. The first skill PR
 * replaces this with the real LLM + notifier flow (mirrors calendar/finance-agent's
 * TriggerController) — e.g. the proactive {@code weekly-review}.
 */
@RestController
@RequestMapping("/agents/tasks/triggers")
public class TriggerController {

    private static final Logger log = LoggerFactory.getLogger(TriggerController.class);

    private final SkillRegistry skills;

    public TriggerController(SkillRegistry skills) {
        this.skills = skills;
    }

    @PostMapping("/{kind}")
    public Mono<ResponseEntity<Void>> trigger(@PathVariable String kind,
                                              @RequestBody AgentWakeRequest req) {
        if (skills.forTrigger(kind).isEmpty()) {
            log.warn("no skill bound to trigger kind={} (schedule={})", kind, req.scheduleId());
            return Mono.just(ResponseEntity.notFound().build());
        }
        // No skills ship in the skeleton, so this branch is currently unreachable; the first
        // skill PR fills in the real dispatch here.
        return Mono.just(ResponseEntity.accepted().build());
    }
}
