package dev.fedorov.ailife.orchestrator.agent;

import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import reactor.core.publisher.Mono;

/**
 * Smallest unit of routing. Agents handle two flavours of input:
 * <ul>
 *   <li>{@link #handle(NormalizedMessage)} — user-initiated intent (orchestrator
 *       picks this agent for an incoming message);</li>
 *   <li>{@link #wake(AgentWakeRequest)} — scheduler-initiated trigger (a
 *       {@code core.schedules} row fired; orchestrator forwards it here).</li>
 * </ul>
 * Local agents that don't care about wake-ups (e.g. {@link EchoAgent}) keep the
 * default no-op. Remote agents (see {@link RemoteAgent}) forward both shapes
 * over HTTP.
 */
public interface Agent {

    String id();

    Mono<IntentResponse> handle(NormalizedMessage message);

    default Mono<Void> wake(AgentWakeRequest request) {
        return Mono.empty();
    }
}
