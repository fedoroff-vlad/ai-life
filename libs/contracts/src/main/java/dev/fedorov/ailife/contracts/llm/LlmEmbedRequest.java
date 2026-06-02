package dev.fedorov.ailife.contracts.llm;

import java.util.List;

public record LlmEmbedRequest(List<String> inputs) {

    public LlmEmbedRequest {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("inputs must not be empty");
        }
        inputs = List.copyOf(inputs);
    }
}
