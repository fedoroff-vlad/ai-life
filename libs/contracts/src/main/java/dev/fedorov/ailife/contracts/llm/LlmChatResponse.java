package dev.fedorov.ailife.contracts.llm;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmChatResponse(
        String model,
        String content,
        String finishReason,
        LlmUsage usage) {
}
