package dev.fedorov.ailife.contracts.llm;

public record LlmUsage(int promptTokens, int completionTokens, int totalTokens) {

    public static LlmUsage zero() {
        return new LlmUsage(0, 0, 0);
    }
}
