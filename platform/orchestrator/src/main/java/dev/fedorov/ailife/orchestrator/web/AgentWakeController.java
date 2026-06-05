package dev.fedorov.ailife.orchestrator.web;

import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import dev.fedorov.ailife.orchestrator.agent.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Entry point scheduler-service hits when a {@code core.schedules} row is due.
 * Looks up the agent in the orchestrator's dispatch map (built at startup from
 * AGENT.md discovery) and calls its {@link Agent#wake(AgentWakeRequest)}; the
 * remote-agent flavour forwards over HTTP to
 * {@code <baseUrl>/agents/<name>/triggers/<kind>}. Unknown agent → 404 so the
 * schedule row stays in due state and retries on the next tick.
 */
@RestController
@RequestMapping("/v1/agents")
public class AgentWakeController {

    private static final Logger log = LoggerFactory.getLogger(AgentWakeController.class);

    private final Map<String, Agent> agents;

    public AgentWakeController(@Qualifier("agentDispatch") Map<String, Agent> agents) {
        this.agents = agents;
    }

    @PostMapping("/wake")
    public Mono<ResponseEntity<Void>> wake(@RequestBody AgentWakeRequest request) {
        Agent target = request.agent() == null ? null : agents.get(request.agent());
        if (target == null) {
            log.warn("wake: no agent registered for name={} (schedule={}, kind={})",
                    request.agent(), request.scheduleId(), request.kind());
            return Mono.just(ResponseEntity.notFound().build());
        }
        log.info("wake: agent={} kind={} schedule={}",
                request.agent(), request.kind(), request.scheduleId());
        return target.wake(request)
                .then(Mono.just(ResponseEntity.<Void>accepted().build()));
    }
}
