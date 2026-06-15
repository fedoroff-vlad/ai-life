package dev.fedorov.ailife.orchestrator.agent;

import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
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

    /**
     * Resume an open question this agent asked (the conversation was route-locked to it). The
     * default treats the reply as a fresh intent — fine for local agents; {@link RemoteAgent}
     * forwards to the agent's {@code /resume} so it can use the stored {@code pendingAction}.
     */
    default Mono<IntentResponse> resume(ResumeRequest request) {
        return handle(request.message());
    }

    default Mono<Void> wake(AgentWakeRequest request) {
        return Mono.empty();
    }

    /**
     * Perform a structured action requested by another agent (Stage 4 / C1 inter-agent
     * sync). Local agents that expose no actions return an error result by default;
     * {@link RemoteAgent} forwards to the agent's {@code /actions/<action>} endpoint.
     */
    default Mono<AgentActionResult> invoke(AgentActionRequest request) {
        return Mono.just(AgentActionResult.error(
                "agent '" + id() + "' does not support action '" + request.action() + "'"));
    }
}
