package dev.fedorov.ailife.tg.orchestrator;

import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class OrchestratorClient {

    private final WebClient http;

    public OrchestratorClient(WebClient orchestratorWebClient) {
        this.http = orchestratorWebClient;
    }

    public Mono<IntentResponse> handle(NormalizedMessage message) {
        return http.post()
                .uri("/v1/intent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(message)
                .retrieve()
                .bodyToMono(IntentResponse.class);
    }

    /**
     * Ask a named agent to perform a structured action, through the hub (Stage 4 / C1
     * {@code POST /v1/agents/invoke}). Used for a front-door event that carries <b>no sentence to
     * classify</b> — a scanned container label (IN-f), where the token itself says where it belongs, so
     * routing it through the LLM classifier would only add a guess.
     *
     * <p>{@link Mono#empty()} when the target agent isn't registered (the hub answers 404) — the caller
     * degrades to a plain "not available" reply instead of an error.
     */
    public Mono<AgentActionResult> invoke(AgentActionRequest request) {
        return http.post()
                .uri("/v1/agents/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, resp -> Mono.empty())
                .bodyToMono(AgentActionResult.class);
    }
}
