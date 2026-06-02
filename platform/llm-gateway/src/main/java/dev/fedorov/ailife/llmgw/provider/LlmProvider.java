package dev.fedorov.ailife.llmgw.provider;

import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmEmbedRequest;
import dev.fedorov.ailife.contracts.llm.LlmEmbedResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Single LLM backend (mock / Anthropic / OpenAI-compatible / Ollama).
 * Chosen by {@code LLM_PROVIDER}; per-channel model names live in
 * {@link dev.fedorov.ailife.llmgw.config.LlmGatewayProperties}.
 */
public interface LlmProvider {

    /** Provider id, matched against {@code LLM_PROVIDER}. */
    String id();

    Mono<LlmChatResponse> chat(LlmChatRequest request);

    /** Server-sent deltas. Mock implementation returns chunks of the same content. */
    Flux<String> chatStream(LlmChatRequest request);

    Mono<LlmEmbedResponse> embed(LlmChannel channel, LlmEmbedRequest request);
}
