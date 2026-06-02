package dev.fedorov.ailife.llmgw.provider.mock;

import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmEmbedRequest;
import dev.fedorov.ailife.contracts.llm.LlmEmbedResponse;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llmgw.config.LlmGatewayProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockProviderTest {

    private final LlmGatewayProperties props = new LlmGatewayProperties();
    private final MockProvider provider = new MockProvider(props);

    @Test
    void echoesLastUserMessageWithChannelPrefix() {
        var request = LlmChatRequest.of(LlmChannel.FAST, List.of(
                LlmMessage.system("you are useful"),
                LlmMessage.user("hello"),
                LlmMessage.assistant("hi"),
                LlmMessage.user("how are you")));

        LlmChatResponse response = provider.chat(request).block();

        assertThat(response).isNotNull();
        assertThat(response.content()).isEqualTo("[fast] how are you");
        assertThat(response.model()).isEqualTo("mock-large");
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(response.usage().totalTokens()).isPositive();
    }

    @Test
    void streamSplitsContentIntoChunks() {
        var request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.user("one two three")));

        List<String> chunks = provider.chatStream(request).collectList().block();

        assertThat(chunks).isNotNull();
        String joined = String.join("", chunks);
        assertThat(joined).isEqualTo("[default] one two three");
        assertThat(chunks.size()).isGreaterThan(1);
    }

    @Test
    void embeddingsAreDeterministic() {
        var req = new LlmEmbedRequest(List.of("hello world", "another input"));

        LlmEmbedResponse first = provider.embed(LlmChannel.EMBEDDING, req).block();
        LlmEmbedResponse second = provider.embed(LlmChannel.EMBEDDING, req).block();

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(first.vectors()).hasSize(2);
        assertThat(first.vectors().get(0)).hasSize(384);
        assertThat(first.vectors().get(0)).isEqualTo(second.vectors().get(0));
        assertThat(first.vectors().get(1)).isEqualTo(second.vectors().get(1));
        assertThat(first.vectors().get(0)).isNotEqualTo(first.vectors().get(1));
    }
}
