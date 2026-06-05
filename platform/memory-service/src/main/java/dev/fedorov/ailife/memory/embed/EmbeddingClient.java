package dev.fedorov.ailife.memory.embed;

import dev.fedorov.ailife.contracts.llm.LlmEmbedRequest;
import dev.fedorov.ailife.contracts.llm.LlmEmbedResponse;
import dev.fedorov.ailife.llm.LlmClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Thin wrapper around {@link LlmClient#embed(LlmEmbedRequest)} that hides the
 * batched-request API behind a single-string call. Memory-service only ever
 * embeds one piece of text at a time (one write, one recall query); batching is
 * a future optimisation for warmup or bulk re-embed.
 */
@Component
public class EmbeddingClient {

    private final LlmClient llm;

    public EmbeddingClient(LlmClient llm) {
        this.llm = llm;
    }

    public float[] embed(String text) {
        LlmEmbedResponse response = llm.embed(new LlmEmbedRequest(List.of(text))).block();
        if (response == null || response.vectors().isEmpty()) {
            throw new IllegalStateException("llm-gateway returned no embedding for input of " + text.length() + " chars");
        }
        return response.vectors().get(0);
    }
}
