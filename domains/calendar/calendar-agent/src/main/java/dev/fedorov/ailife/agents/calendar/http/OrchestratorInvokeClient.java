package dev.fedorov.ailife.agents.calendar.http;

import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Calls another agent through the orchestrator's sync hub ({@code POST /v1/agents/invoke}).
 * Agents never call each other directly — this is the locked inter-agent path. Used by the
 * gift-recommender coordinator flow (Stage 4 / Track D) to ask finance-agent for the
 * household's {@code get_gift_budget}. Mirrors tasks-agent's client (C1e / PR76).
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
                .timeout(Duration.ofSeconds(5));
    }
}
