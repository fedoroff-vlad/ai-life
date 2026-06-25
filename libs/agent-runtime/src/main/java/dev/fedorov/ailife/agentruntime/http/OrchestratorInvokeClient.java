package dev.fedorov.ailife.agentruntime.http;

import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Shared inter-agent hub client. Calls another agent through the orchestrator's sync hub
 * ({@code POST /v1/agents/invoke}) — agents never call each other directly (the locked inter-agent
 * path, architecture.md §Decisions). The {@link WebClient} bean named {@code orchestratorWebClient} is
 * owned by each agent that talks to the hub (it binds the base URL from per-agent properties); this
 * class is purely the request shape. Bound by the gift-recommender / birthday-greeter (calendar), the
 * ration→recipes flow (nutritionist), and the task→event flow (tasks).
 */
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
     * (e.g. {@code creator.draft_greeting}, {@code chef.recommend_recipes}), where the passthrough
     * default is too tight.
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
