package dev.fedorov.ailife.orchestrator.routing;

import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.orchestrator.agent.Agent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Picks an agent for a {@link NormalizedMessage}. Stage 0 — always {@code echo}.
 * Stage 1+ — replace {@link #pick(NormalizedMessage)} with intent classification
 * via the {@code fast} LLM channel.
 */
@Component
public class IntentRouter {

    private final Map<String, Agent> agents;

    public IntentRouter(List<Agent> agents) {
        this.agents = agents.stream()
                .collect(Collectors.toUnmodifiableMap(Agent::id, Function.identity()));
    }

    public Mono<IntentResponse> route(NormalizedMessage message) {
        Agent target = pick(message);
        return target.handle(message);
    }

    Agent pick(NormalizedMessage message) {
        return agents.get("echo");
    }

    public boolean has(String agentName) {
        return agents.containsKey(agentName);
    }
}
