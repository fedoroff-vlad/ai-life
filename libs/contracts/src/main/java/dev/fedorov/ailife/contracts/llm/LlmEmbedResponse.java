package dev.fedorov.ailife.contracts.llm;

import java.util.List;

public record LlmEmbedResponse(String model, List<float[]> vectors, LlmUsage usage) {

    public LlmEmbedResponse {
        vectors = vectors == null ? List.of() : List.copyOf(vectors);
    }
}
