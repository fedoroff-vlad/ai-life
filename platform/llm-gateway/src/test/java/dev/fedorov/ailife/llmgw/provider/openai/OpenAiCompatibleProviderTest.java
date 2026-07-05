package dev.fedorov.ailife.llmgw.provider.openai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmEmbedRequest;
import dev.fedorov.ailife.contracts.llm.LlmEmbedResponse;
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

class OpenAiCompatibleProviderTest {

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

    private LlmGatewayProperties baseProps() {
        LlmGatewayProperties p = new LlmGatewayProperties();
        p.setProvider("openai-compatible");
        p.setBaseUrl(server.url("/v1").toString().replaceAll("/$", ""));
        p.setDefaultModel("qwen2.5:72b-instruct");
        p.setFastModel("qwen2.5:7b-instruct");
        p.setEmbeddingModel("bge-m3");
        return p;
    }

    private OpenAiCompatibleProvider provider(LlmGatewayProperties props) {
        return new OpenAiCompatibleProvider(props, WebClient.builder());
    }

    @Test
    void chatSendsCanonicalBodyAndParsesResponse() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("""
                        {
                          "model": "qwen2.5:72b-instruct",
                          "choices": [{
                            "message": {"role": "assistant", "content": "hi there"},
                            "finish_reason": "stop"
                          }],
                          "usage": {"prompt_tokens": 9, "completion_tokens": 2, "total_tokens": 11}
                        }
                        """));

        LlmGatewayProperties props = baseProps();
        props.setApiKey("sk-test");
        OpenAiCompatibleProvider provider = provider(props);
        LlmChatRequest req = new LlmChatRequest(LlmChannel.DEFAULT, List.of(
                LlmMessage.system("you are kind"),
                LlmMessage.user("hello")), 128, 0.2);

        LlmChatResponse resp = provider.chat(req).block();

        assertThat(resp).isNotNull();
        assertThat(resp.content()).isEqualTo("hi there");
        assertThat(resp.model()).isEqualTo("qwen2.5:72b-instruct");
        assertThat(resp.finishReason()).isEqualTo("stop");
        assertThat(resp.usage().promptTokens()).isEqualTo(9);
        assertThat(resp.usage().completionTokens()).isEqualTo(2);
        assertThat(resp.usage().totalTokens()).isEqualTo(11);

        RecordedRequest sent = server.takeRequest();
        assertThat(sent.getMethod()).isEqualTo("POST");
        assertThat(sent.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer sk-test");

        JsonNode body = MAPPER.readTree(sent.getBody().readUtf8());
        assertThat(body.path("model").asText()).isEqualTo("qwen2.5:72b-instruct");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(128);
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(body.has("stream")).isFalse();
        JsonNode messages = body.path("messages");
        assertThat(messages.size()).isEqualTo(2);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
        assertThat(messages.get(0).path("content").asText()).isEqualTo("you are kind");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
    }

    @Test
    void ollamaStyleNoAuthHeaderWhenKeyBlank() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("""
                        {"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                        """));

        LlmGatewayProperties props = baseProps();
        // no api key — local Ollama
        OpenAiCompatibleProvider provider = provider(props);

        provider.chat(new LlmChatRequest(LlmChannel.FAST,
                List.of(LlmMessage.user("ping")), null, null)).block();

        RecordedRequest sent = server.takeRequest();
        assertThat(sent.getHeader("Authorization")).isNull();
        JsonNode body = MAPPER.readTree(sent.getBody().readUtf8());
        assertThat(body.path("model").asText()).isEqualTo("qwen2.5:7b-instruct");
        assertThat(body.has("max_tokens")).isFalse();
        assertThat(body.has("temperature")).isFalse();
    }

    @Test
    void visionMessageBecomesTextPlusImageUrlParts() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("""
                        {"model":"qwen2.5-vl:32b",
                         "choices":[{"message":{"role":"assistant","content":"a receipt"},"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":20,"completion_tokens":3,"total_tokens":23}}
                        """));

        LlmGatewayProperties props = baseProps();
        props.setVisionModel("qwen2.5-vl:32b");
        OpenAiCompatibleProvider provider = provider(props);
        LlmChatRequest req = LlmChatRequest.of(LlmChannel.VISION, List.of(
                LlmMessage.userWithImages("what is this?",
                        List.of(new LlmImage("image/png", "QUJD")))));

        provider.chat(req).block();

        RecordedRequest sent = server.takeRequest();
        JsonNode body = MAPPER.readTree(sent.getBody().readUtf8());
        assertThat(body.path("model").asText()).isEqualTo("qwen2.5-vl:32b");
        JsonNode content = body.path("messages").get(0).path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.size()).isEqualTo(2);
        assertThat(content.get(0).path("type").asText()).isEqualTo("text");
        assertThat(content.get(0).path("text").asText()).isEqualTo("what is this?");
        assertThat(content.get(1).path("type").asText()).isEqualTo("image_url");
        assertThat(content.get(1).path("image_url").path("url").asText())
                .isEqualTo("data:image/png;base64,QUJD");
    }

    @Test
    void streamParsesDeltaContentAndStopsAtDone() {
        String sse = ""
                + "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"hello \"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"world\"}}]}\n\n"
                + "data: [DONE]\n\n";
        server.enqueue(new MockResponse()
                .setHeader("content-type", "text/event-stream")
                .setBody(sse));

        OpenAiCompatibleProvider provider = provider(baseProps());
        List<String> chunks = provider.chatStream(LlmChatRequest.of(LlmChannel.DEFAULT,
                List.of(LlmMessage.user("ping")))).collectList().block();

        assertThat(chunks).containsExactly("hello ", "world");
    }

    @Test
    void streamRequestSetsStreamFlag() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("content-type", "text/event-stream")
                .setBody("data: [DONE]\n\n"));

        OpenAiCompatibleProvider provider = provider(baseProps());
        provider.chatStream(LlmChatRequest.of(LlmChannel.DEFAULT,
                List.of(LlmMessage.user("hi")))).collectList().block();

        RecordedRequest sent = server.takeRequest();
        JsonNode body = MAPPER.readTree(sent.getBody().readUtf8());
        assertThat(body.path("stream").asBoolean()).isTrue();
    }

    @Test
    void embedRoundTrip() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("""
                        {
                          "model": "bge-m3",
                          "data": [
                            {"embedding": [0.1, 0.2, 0.3], "index": 0},
                            {"embedding": [0.4, 0.5, 0.6], "index": 1}
                          ],
                          "usage": {"prompt_tokens": 7, "total_tokens": 7}
                        }
                        """));

        OpenAiCompatibleProvider provider = provider(baseProps());
        LlmEmbedResponse resp = provider.embed(LlmChannel.EMBEDDING,
                new LlmEmbedRequest(List.of("first", "second"))).block();

        assertThat(resp).isNotNull();
        assertThat(resp.model()).isEqualTo("bge-m3");
        assertThat(resp.vectors()).hasSize(2);
        assertThat(resp.vectors().get(0)).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(resp.vectors().get(1)).containsExactly(0.4f, 0.5f, 0.6f);
        assertThat(resp.usage().promptTokens()).isEqualTo(7);

        RecordedRequest sent = server.takeRequest();
        assertThat(sent.getPath()).isEqualTo("/v1/embeddings");
        JsonNode body = MAPPER.readTree(sent.getBody().readUtf8());
        assertThat(body.path("model").asText()).isEqualTo("bge-m3");
        JsonNode input = body.path("input");
        assertThat(input.isArray()).isTrue();
        assertThat(input.get(0).asText()).isEqualTo("first");
        assertThat(input.get(1).asText()).isEqualTo("second");
    }

    @Test
    void chatPropagatesUpstreamErrorWithBody() {
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setBody("{\"error\":{\"message\":\"slow down\"}}"));

        OpenAiCompatibleProvider provider = provider(baseProps());
        assertThatThrownBy(() -> provider.chat(LlmChatRequest.of(LlmChannel.DEFAULT,
                List.of(LlmMessage.user("hi")))).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("429")
                .hasMessageContaining("slow down");
    }

    @Test
    void missingBaseUrlFailsFast() {
        LlmGatewayProperties props = new LlmGatewayProperties();
        props.setProvider("openai-compatible");
        // no base url
        assertThatThrownBy(() -> new OpenAiCompatibleProvider(props, WebClient.builder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM_BASE_URL");
    }
}
