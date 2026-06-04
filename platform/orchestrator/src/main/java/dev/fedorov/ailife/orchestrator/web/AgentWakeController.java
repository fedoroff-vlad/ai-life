package dev.fedorov.ailife.orchestrator.web;

import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import dev.fedorov.ailife.orchestrator.routing.IntentRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entry point scheduler-service hits when a {@code core.schedules} row is due.
 * For now this is an ack-only stub: it checks the named agent exists in the
 * router's registry and returns 202 / 404. Actual dispatch into the agent's
 * wake handler lands when calendar-agent (and the wake-handler interface)
 * ships — at that point this method routes the payload into the agent.
 */
@RestController
@RequestMapping("/v1/agents")
public class AgentWakeController {

    private static final Logger log = LoggerFactory.getLogger(AgentWakeController.class);

    private final IntentRouter router;

    public AgentWakeController(IntentRouter router) {
        this.router = router;
    }

    @PostMapping("/wake")
    public ResponseEntity<Void> wake(@RequestBody AgentWakeRequest request) {
        if (request.agent() == null || !router.has(request.agent())) {
            log.warn("wake: no agent registered for name={} (schedule={}, kind={})",
                    request.agent(), request.scheduleId(), request.kind());
            return ResponseEntity.notFound().build();
        }
        log.info("wake: agent={} kind={} schedule={}",
                request.agent(), request.kind(), request.scheduleId());
        return ResponseEntity.accepted().build();
    }
}
