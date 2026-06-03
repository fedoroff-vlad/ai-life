package dev.fedorov.ailife.orchestrator.agent;

import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import reactor.core.publisher.Mono;

/**
 * Smallest unit of routing. PR5 ships only {@link EchoAgent}; later PRs add calendar,
 * finance, tasks, etc. Future intent classification picks an agent here.
 */
public interface Agent {

    String id();

    Mono<IntentResponse> handle(NormalizedMessage message);
}
