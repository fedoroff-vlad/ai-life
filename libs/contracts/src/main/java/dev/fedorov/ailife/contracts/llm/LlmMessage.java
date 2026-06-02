package dev.fedorov.ailife.contracts.llm;

import com.fasterxml.jackson.annotation.JsonInclude;

public record LlmMessage(
        LlmRole role,
        String content,
        @JsonInclude(JsonInclude.Include.NON_NULL) String name) {

    public static LlmMessage system(String content) {
        return new LlmMessage(LlmRole.SYSTEM, content, null);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(LlmRole.USER, content, null);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage(LlmRole.ASSISTANT, content, null);
    }
}
