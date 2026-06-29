package dev.fedorov.ailife.contracts.llm;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmChatRequest(
        LlmChannel channel,
        List<LlmMessage> messages,
        Integer maxTokens,
        Double temperature) {

    public LlmChatRequest {
        if (channel == null) {
            channel = LlmChannel.DEFAULT;
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        messages = List.copyOf(messages);
    }

    public static LlmChatRequest of(LlmChannel channel, List<LlmMessage> messages) {
        return new LlmChatRequest(channel, messages, null, null);
    }

    /**
     * A request pinned to a {@code temperature} — use {@code 0.0} for deterministic <b>extraction</b>
     * skills (a typed message → strict JSON), where faithful, repeatable output matters more than
     * variety. The provider passes it through to the upstream API.
     */
    public static LlmChatRequest of(LlmChannel channel, List<LlmMessage> messages, Double temperature) {
        return new LlmChatRequest(channel, messages, null, temperature);
    }
}
