package dev.fedorov.ailife.agents.calendar.web;

import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hit by orchestrator when scheduler-service wakes this agent for a {@code kind}
 * declared in {@link dev.fedorov.ailife.agents.calendar.manifest.AgentManifest#triggers()}.
 * PR9b stub — ack-only; per-kind handlers (birthday.greet, gift.recommend) land in PR9c.
 */
@RestController
@RequestMapping("/agents/calendar/triggers")
public class TriggerController {

    private static final Logger log = LoggerFactory.getLogger(TriggerController.class);

    @PostMapping("/{kind}")
    public ResponseEntity<Void> trigger(@PathVariable String kind, @RequestBody AgentWakeRequest req) {
        log.info("trigger stub: kind={} scheduleId={} household={}",
                kind, req.scheduleId(), req.householdId());
        return ResponseEntity.accepted().build();
    }
}
