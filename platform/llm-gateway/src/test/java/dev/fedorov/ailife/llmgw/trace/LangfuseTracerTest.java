package dev.fedorov.ailife.llmgw.trace;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmEmbedRequest;
import dev.fedorov.ailife.contracts.llm.LlmEmbedResponse;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.llmgw.config.LangfuseProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LangfuseTracerTest {

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

    private LangfuseProperties enabledProps() {
        LangfuseProperties props = new LangfuseProperties();
        props.setEnabled(true);
        props.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        props.setPublicKey("pk");
        props.setSecretKey("sk");
        return props;
    }

    private LlmChatRequest sampleRequest() {
        return LlmChatRequest.of(LlmChannel.FAST, List.of(
                LlmMessage.system("you are terse"),
                LlmMessage.user("привет")));
    }

    private LlmChatResponse sampleResponse() {
        return new LlmChatResponse("claude-haiku-4-5", "здравствуйте", "end_turn",
                new LlmUsage(12, 3, 15));
    }

    @Test
    void enabledTracerPostsTraceAndGenerationWithUsageAndBasicAuth() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(207));

        LangfuseProperties props = new LangfuseProperties();
        props.setEnabled(true);
        props.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        props.setPublicKey("pk-lf-test");
        props.setSecretKey("sk-lf-test");
        LangfuseTracer tracer = new LangfuseTracer(props, WebClient.builder());

        Instant start = Instant.now();
        tracer.traceChat(sampleRequest(), sampleResponse(), start, start.plusMillis(40)).block();

        RecordedRequest rq = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(rq).isNotNull();
        assertThat(rq.getPath()).isEqualTo("/api/public/ingestion");
        assertThat(rq.getHeader("Authorization"))
                .isEqualTo("Basic " + Base64.getEncoder()
                        .encodeToString("pk-lf-test:sk-lf-test".getBytes()));

        JsonNode batch = MAPPER.readTree(rq.getBody().readUtf8()).get("batch");
        assertThat(batch).hasSize(2);

        JsonNode trace = batch.get(0);
        assertThat(trace.get("type").asText()).isEqualTo("trace-create");
        assertThat(trace.path("body").path("name").asText()).isEqualTo("llm-gateway.chat");
        assertThat(trace.path("body").path("metadata").path("channel").asText()).isEqualTo("fast");

        JsonNode gen = batch.get(1);
        assertThat(gen.get("type").asText()).isEqualTo("generation-create");
        JsonNode genBody = gen.get("body");
        assertThat(genBody.path("traceId").asText()).isEqualTo(trace.path("body").path("id").asText());
        assertThat(genBody.path("type").asText()).isEqualTo("GENERATION");
        assertThat(genBody.path("model").asText()).isEqualTo("claude-haiku-4-5");
        assertThat(genBody.path("output").asText()).isEqualTo("здравствуйте");
        assertThat(genBody.path("usage").path("input").asInt()).isEqualTo(12);
        assertThat(genBody.path("usage").path("output").asInt()).isEqualTo(3);
        assertThat(genBody.path("usage").path("total").asInt()).isEqualTo(15);
        assertThat(genBody.path("usage").path("unit").asText()).isEqualTo("TOKENS");
        // input carries the chat turns role+content
        assertThat(genBody.path("input")).hasSize(2);
        assertThat(genBody.path("input").get(0).path("role").asText()).isEqualTo("system");
        assertThat(genBody.path("metadata").path("finishReason").asText()).isEqualTo("end_turn");
    }

    @Test
    void streamTraceCarriesAccumulatedOutputAndOmitsUsage() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(207));

        LangfuseProperties props = enabledProps();
        LangfuseTracer tracer = new LangfuseTracer(props, WebClient.builder());

        Instant start = Instant.now();
        tracer.traceChatStream(sampleRequest(), "здрав-ствуй-те", "qwen2.5:7b-instruct",
                start, start.plusMillis(80)).block();

        RecordedRequest rq = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(rq).isNotNull();
        assertThat(rq.getPath()).isEqualTo("/api/public/ingestion");

        JsonNode batch = MAPPER.readTree(rq.getBody().readUtf8()).get("batch");
        assertThat(batch).hasSize(2);
        assertThat(batch.get(0).path("body").path("name").asText()).isEqualTo("llm-gateway.chat.stream");

        JsonNode genBody = batch.get(1).path("body");
        assertThat(genBody.path("model").asText()).isEqualTo("qwen2.5:7b-instruct");
        assertThat(genBody.path("output").asText()).isEqualTo("здрав-ствуй-те");
        assertThat(genBody.path("metadata").path("streamed").asBoolean()).isTrue();
        // Streaming reports no token usage — the generation must omit the usage block.
        assertThat(genBody.has("usage")).isFalse();
    }

    @Test
    void embedTraceCarriesModelInputsUsageAndVectorMetadata() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(207));

        LangfuseProperties props = enabledProps();
        LangfuseTracer tracer = new LangfuseTracer(props, WebClient.builder());

        LlmEmbedRequest req = new LlmEmbedRequest(List.of("first", "second"));
        LlmEmbedResponse resp = new LlmEmbedResponse("bge-m3",
                List.of(new float[3], new float[3]), new LlmUsage(8, 0, 8));

        Instant start = Instant.now();
        tracer.traceEmbed(req, resp, start, start.plusMillis(20)).block();

        RecordedRequest rq = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(rq).isNotNull();
        JsonNode batch = MAPPER.readTree(rq.getBody().readUtf8()).get("batch");
        assertThat(batch.get(0).path("body").path("name").asText()).isEqualTo("llm-gateway.embed");

        JsonNode genBody = batch.get(1).path("body");
        assertThat(genBody.path("model").asText()).isEqualTo("bge-m3");
        assertThat(genBody.path("metadata").path("channel").asText()).isEqualTo("embedding");
        assertThat(genBody.path("metadata").path("vectorCount").asInt()).isEqualTo(2);
        assertThat(genBody.path("metadata").path("dimensions").asInt()).isEqualTo(3);
        assertThat(genBody.path("input")).hasSize(2);
        assertThat(genBody.path("input").get(0).asText()).isEqualTo("first");
        assertThat(genBody.path("usage").path("input").asInt()).isEqualTo(8);
        assertThat(genBody.path("usage").path("total").asInt()).isEqualTo(8);
    }

    @Test
    void disabledTracerMakesNoHttpCall() throws Exception {
        LangfuseProperties props = new LangfuseProperties();
        props.setEnabled(false);
        props.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        LangfuseTracer tracer = new LangfuseTracer(props, WebClient.builder());

        // Disabled → empty Mono, no request enqueued or sent.
        tracer.traceChat(sampleRequest(), sampleResponse(), Instant.now(), Instant.now()).block();

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void ingestionFailureIsSwallowed() {
        server.enqueue(new MockResponse().setResponseCode(500));

        LangfuseTracer tracer = new LangfuseTracer(enabledProps(), WebClient.builder());

        // A 500 from Langfuse must not surface — block() completes without throwing.
        tracer.traceChat(sampleRequest(), sampleResponse(), Instant.now(), Instant.now()).block();
    }
}
