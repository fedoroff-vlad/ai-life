package dev.fedorov.ailife.llm;

import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmEmbedRequest;
import dev.fedorov.ailife.contracts.llm.LlmEmbedResponse;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Thin reactive client for llm-gateway. Agents do not know which provider is active —
 * they just pick a channel ({@code default} / {@code fast} / {@code vision} / {@code embedding}).
 */
public class LlmClient {

    private final WebClient http;

    public LlmClient(WebClient http) {
        this.http = http;
    }

    public Mono<LlmChatResponse> chat(LlmChatRequest request) {
        return http.post()
                .uri("/v1/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(LlmChatResponse.class);
    }

    public Flux<String> chatStream(LlmChatRequest request) {
        return http.post()
                .uri("/v1/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class);
    }

    public Mono<LlmEmbedResponse> embed(LlmEmbedRequest request) {
        return http.post()
                .uri("/v1/embed")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(LlmEmbedResponse.class);
    }
}
