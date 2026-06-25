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
 * household's {@code get_gift_budget}, and by the birthday-greeter (CR-g2) to ask the creator
 * for {@code draft_greeting}. Mirrors tasks-agent's client (C1e / PR76).
 */
@Component
public class OrchestratorInvokeClient {

    /** Default for fast passthrough actions (e.g. a DB-read budget lookup). */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final WebClient http;

    public OrchestratorInvokeClient(@Qualifier("orchestratorWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<AgentActionResult> invoke(AgentActionRequest request) {
        return invoke(request, DEFAULT_TIMEOUT);
    }

    /**
     * Invoke with an explicit timeout — for an action that fronts an LLM call on the target side
     * (e.g. {@code creator.draft_greeting}), where the 5s passthrough default is too tight.
     */
    public Mono<AgentActionResult> invoke(AgentActionRequest request, Duration timeout) {
        return http.post()
                .uri("/v1/agents/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AgentActionResult.class)
                .timeout(timeout);
    }
}
