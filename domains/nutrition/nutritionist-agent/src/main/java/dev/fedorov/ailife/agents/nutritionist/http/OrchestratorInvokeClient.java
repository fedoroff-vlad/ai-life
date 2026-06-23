package dev.fedorov.ailife.agents.nutritionist.http;

import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Calls another agent through the orchestrator's sync hub ({@code POST /v1/agents/invoke}). Agents
 * never call each other directly — this is the locked inter-agent path. Used by the ration flow
 * (NU-g) to invoke the chef's {@code recommend_recipes} (ration → recipes). Mirrors calendar-agent's
 * gift-recommender → finance client.
 */
@Component
public class OrchestratorInvokeClient {

    private final WebClient http;

    public OrchestratorInvokeClient(@Qualifier("orchestratorWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<AgentActionResult> invoke(AgentActionRequest request) {
        return http.post()
                .uri("/v1/agents/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AgentActionResult.class)
                .timeout(Duration.ofSeconds(8));
    }
}
