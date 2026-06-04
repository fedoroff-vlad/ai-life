package dev.fedorov.ailife.orchestrator.agent;

import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Calls a remote agent service at {@code POST /agents/<name>/intent}. The
 * orchestrator creates one of these per entry in
 * {@link AgentRegistryProperties#getAgents()} whose manifest fetched successfully.
 */
public class RemoteAgent implements Agent {

    private final String name;
    private final WebClient http;

    public RemoteAgent(String name, WebClient http) {
        this.name = name;
        this.http = http;
    }

    @Override
    public String id() {
        return name;
    }

    @Override
    public Mono<IntentResponse> handle(NormalizedMessage message) {
        return http.post()
                .uri("/agents/" + name + "/intent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(message)
                .retrieve()
                .bodyToMono(IntentResponse.class);
    }
}
