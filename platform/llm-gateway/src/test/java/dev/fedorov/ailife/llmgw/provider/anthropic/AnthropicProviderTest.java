package dev.fedorov.ailife.llmgw.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmEmbedRequest;
import dev.fedorov.ailife.contracts.llm.LlmImage;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llmgw.config.LlmGatewayProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnthropicProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockWebServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private AnthropicProvider providerWithProps(LlmGatewayProperties props) {
        props.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        return new AnthropicProvider(props, WebClient.builder());
    }

    private LlmGatewayProperties baseProps() {
        LlmGatewayProperties p = new LlmGatewayProperties();
        p.setProvider("anthropic");
        p.setApiKey("sk-test");
        p.setDefaultModel("claude-opus-4-7");
        p.setFastModel("claude-haiku-4-5");
        return p;
    }

    @Test
    void chatSendsSystemSeparatelyAndParsesResponse() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("""
                        {
                          "id": "msg_01",
                          "model": "claude-opus-4-7",
                          "stop_reason": "end_turn",
                          "content": [{"type":"text","text":"привет"}],
                          "usage": {"input_tokens": 12, "output_tokens": 4}
                        }
                        """));

        AnthropicProvider provider = providerWithProps(baseProps());
        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system("you are a friend"),
                LlmMessage.system("answer briefly"),
                LlmMessage.user("hi"),
                LlmMessage.assistant("hey"),
                LlmMessage.user("how are you")));

        LlmChatResponse resp = provider.chat(req).block();

        assertThat(resp).isNotNull();
        assertThat(resp.content()).isEqualTo("привет");
        assertThat(resp.model()).isEqualTo("claude-opus-4-7");
        assertThat(resp.finishReason()).isEqualTo("end_turn");
        assertThat(resp.usage().promptTokens()).isEqualTo(12);
        assertThat(resp.usage().completionTokens()).isEqualTo(4);
        assertThat(resp.usage().totalTokens()).isEqualTo(16);

        RecordedRequest sent = server.takeRequest();
        assertThat(sent.getMethod()).isEqualTo("POST");
        assertThat(sent.getPath()).isEqualTo("/v1/messages");
        assertThat(sent.getHeader("x-api-key")).isEqualTo("sk-test");
        assertThat(sent.getHeader("anthropic-version")).isEqualTo("2023-06-01");

        JsonNode body = MAPPER.readTree(sent.getBody().readUtf8());
        assertThat(body.path("model").asText()).isEqualTo("claude-opus-4-7");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(4096);
        assertThat(body.path("system").asText()).isEqualTo("you are a friend\n\nanswer briefly");
        assertThat(body.has("stream")).isFalse();
        JsonNode messages = body.path("messages");
        assertThat(messages.isArray()).isTrue();
        assertThat(messages.size()).isEqualTo(3);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("user");
        assertThat(messages.get(0).path("content").asText()).isEqualTo("hi");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(2).path("role").asText()).isEqualTo("user");
        assertThat(messages.get(2).path("content").asText()).isEqualTo("how are you");
    }

    @Test
    void chatUsesChannelSpecificModel() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("""
                        {"model":"claude-haiku-4-5","stop_reason":"end_turn",
                         "content":[{"type":"text","text":"ok"}],
                         "usage":{"input_tokens":1,"output_tokens":1}}
                        """));

        AnthropicProvider provider = providerWithProps(baseProps());
        LlmChatRequest req = new LlmChatRequest(LlmChannel.FAST,
                List.of(LlmMessage.user("classify")), 64, 0.0);

        provider.chat(req).block();

        RecordedRequest sent = server.takeRequest();
        JsonNode body = MAPPER.readTree(sent.getBody().readUtf8());
        assertThat(body.path("model").asText()).isEqualTo("claude-haiku-4-5");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(64);
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.0);
    }

    @Test
    void visionMessageBecomesTextPlusImageBlocks() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("""
                        {"model":"claude-opus-4-7","stop_reason":"end_turn",
                         "content":[{"type":"text","text":"a receipt"}],
                         "usage":{"input_tokens":20,"output_tokens":3}}
                        """));

        LlmGatewayProperties props = baseProps();
        props.setVisionModel("claude-opus-4-7");
        AnthropicProvider provider = providerWithProps(props);
        LlmChatRequest req = LlmChatRequest.of(LlmChannel.VISION, List.of(
                LlmMessage.system("describe the photo"),
                LlmMessage.userWithImages("what is this?",
                        List.of(new LlmImage("image/jpeg", "QUJD")))));

        provider.chat(req).block();

        RecordedRequest sent = server.takeRequest();
        JsonNode body = MAPPER.readTree(sent.getBody().readUtf8());
        assertThat(body.path("model").asText()).isEqualTo("claude-opus-4-7");
        assertThat(body.path("system").asText()).isEqualTo("describe the photo");
        JsonNode content = body.path("messages").get(0).path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.size()).isEqualTo(2);
        assertThat(content.get(0).path("type").asText()).isEqualTo("text");
        assertThat(content.get(0).path("text").asText()).isEqualTo("what is this?");
        assertThat(content.get(1).path("type").asText()).isEqualTo("image");
        JsonNode source = content.get(1).path("source");
        assertThat(source.path("type").asText()).isEqualTo("base64");
        assertThat(source.path("media_type").asText()).isEqualTo("image/jpeg");
        assertThat(source.path("data").asText()).isEqualTo("QUJD");
    }

    @Test
    void streamParsesContentBlockDeltas() {
        // Two text deltas + one ignored event type. Each SSE record is `data: <json>\n\n`.
        String sse = ""
                + "event: message_start\n"
                + "data: {\"type\":\"message_start\"}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"hello \"}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"world\"}}\n\n"
                + "event: message_stop\n"
                + "data: {\"type\":\"message_stop\"}\n\n";
        server.enqueue(new MockResponse()
                .setHeader("content-type", "text/event-stream")
                .setBody(sse));

        AnthropicProvider provider = providerWithProps(baseProps());
        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.user("ping")));

        List<String> chunks = provider.chatStream(req).collectList().block();

        assertThat(chunks).containsExactly("hello ", "world");
    }

    @Test
    void streamRequestSetsStreamFlag() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("content-type", "text/event-stream")
                .setBody(""));

        AnthropicProvider provider = providerWithProps(baseProps());
        provider.chatStream(LlmChatRequest.of(LlmChannel.DEFAULT,
                List.of(LlmMessage.user("hi")))).collectList().block();

        RecordedRequest sent = server.takeRequest();
        JsonNode body = MAPPER.readTree(sent.getBody().readUtf8());
        assertThat(body.path("stream").asBoolean()).isTrue();
    }

    @Test
    void chatPropagatesUpstreamErrorWithBody() {
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setBody("{\"error\":{\"type\":\"rate_limit_error\"}}"));

        AnthropicProvider provider = providerWithProps(baseProps());
        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.user("hi")));

        assertThatThrownBy(() -> provider.chat(req).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("429")
                .hasMessageContaining("rate_limit_error");
    }

    @Test
    void missingApiKeyFailsFast() {
        LlmGatewayProperties props = baseProps();
        props.setApiKey(" ");
        assertThatThrownBy(() -> new AnthropicProvider(props, WebClient.builder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM_API_KEY");
    }

    @Test
    void embedIsUnsupported() {
        AnthropicProvider provider = providerWithProps(baseProps());
        assertThatThrownBy(() ->
                provider.embed(LlmChannel.EMBEDDING, new LlmEmbedRequest(List.of("x"))).block())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("no embeddings");
    }
}
