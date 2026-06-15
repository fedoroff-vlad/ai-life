package dev.fedorov.ailife.orchestrator.web;

import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
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
 * Synchronous inter-agent entry point (Stage 4 / C1). One agent asks another to perform
 * a structured action by POSTing an {@link AgentActionRequest} here; the orchestrator
 * looks up {@code targetAgent} and forwards to its
 * {@code POST /agents/<name>/actions/<action>}, relaying the {@link AgentActionResult}.
 * Agents never call each other directly — this keeps the orchestrator the single sync hub
 * (architecture.md §Decisions). Unknown target agent → 404.
 */
@RestController
@RequestMapping("/v1/agents")
public class AgentInvokeController {

    private static final Logger log = LoggerFactory.getLogger(AgentInvokeController.class);

    private final Map<String, Agent> agents;

    public AgentInvokeController(@Qualifier("agentDispatch") Map<String, Agent> agents) {
        this.agents = agents;
    }

    @PostMapping("/invoke")
    public Mono<ResponseEntity<AgentActionResult>> invoke(@RequestBody AgentActionRequest request) {
        Agent target = request.targetAgent() == null ? null : agents.get(request.targetAgent());
        if (target == null) {
            log.warn("invoke: no agent registered for target={} (action={}, requestedBy={})",
                    request.targetAgent(), request.action(), request.requestingAgent());
            return Mono.just(ResponseEntity.notFound().build());
        }
        log.info("invoke: target={} action={} requestedBy={}",
                request.targetAgent(), request.action(), request.requestingAgent());
        return target.invoke(request).map(ResponseEntity::ok);
    }
}
