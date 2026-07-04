package dev.fedorov.ailife.agents.coordinator.web;

import dev.fedorov.ailife.agents.coordinator.flow.MultiDomainCoordinator;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Hit by the orchestrator when intent routing selects {@code coordinator} — i.e. the classifier judged
 * the message cross-cutting (spanning several domains). The single job is
 * {@link MultiDomainCoordinator#handle}: gather from the second brain, synthesize one grounded answer.
 */
@RestController
@RequestMapping("/agents/coordinator")
public class IntentController {

    private final MultiDomainCoordinator coordinator;

    public IntentController(MultiDomainCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        return coordinator.handle(message);
    }
}
