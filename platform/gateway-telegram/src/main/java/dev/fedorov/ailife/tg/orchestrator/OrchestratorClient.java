package dev.fedorov.ailife.tg.orchestrator;

import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
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
}
