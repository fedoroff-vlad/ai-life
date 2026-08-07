package dev.fedorov.ailife.llmgw.e2e;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.ollama.OllamaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Opt-in, self-contained real-model E2E</b> (#297) — the slow variant the mock-provider unit
 * tests can't be: it drives the <b>real</b> {@code OpenAiCompatibleProvider} path against a live
 * model, so the parse surface the {@code MockProvider} fakes is exercised for real — Ollama's model
 * echo, its actual token accounting, the {@code choices[0].message.content} / {@code finish_reason}
 * shape of a genuine completion, and the SSE {@code delta.content} stream. Those are exactly the
 * regression classes the per-seam mocks miss (JSON-shape drift, real usage numbers, stream framing).
 *
 * <p><b>Self-contained</b> — unlike the Stage-5 golden tests ({@code @GoldenLlmTest} via
 * {@code scripts/golden.sh}), which point at a manually-started Ollama + gateway, this owns its whole
 * stack: Testcontainers boots Ollama, pulls the model, and the gateway's real Spring context is wired
 * at it. Nothing external to start.
 *
 * <p><b>Gated, so CI never pays for it.</b> {@code @Tag("slow")} + an env gate mean a normal
 * {@code mvn test} (and CI, where {@code SLOW_LLM_E2E} is unset) <b>skips the whole class</b> before
 * Testcontainers touches Docker — no image pull, no model download. Run it deliberately:
 *
 * <pre>{@code
 *   SLOW_LLM_E2E=true mvn -pl platform/llm-gateway -Dtest=RealOllamaChatSlowE2ETest test
 *   # override the model / image if you have a stronger local one:
 *   SLOW_LLM_E2E=true SLOW_LLM_E2E_MODEL=qwen3:8b mvn -pl platform/llm-gateway -Dtest=RealOllamaChatSlowE2ETest test
 * }</pre>
 *
 * <p>The default model is a small instruction-tuned one so a first run on a CPU box is bearable; it is
 * only smart enough for the two contracts asserted here (a non-empty completion + a parseable JSON
 * object when asked). {@code suppress-thinking} is on so a Qwen3-style override behaves (harmless on a
 * non-thinking model). Assertions are <b>structural, not textual</b> — never the model's exact words.
 */
@Tag("slow")
@EnabledIfEnvironmentVariable(named = "SLOW_LLM_E2E", matches = "(?i)1|true|yes|on")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "llm.provider=openai-compatible",
        "llm.suppress-thinking=true",
        // A CPU model generating a few tokens still dwarfs the 60s cloud default.
        "llm.request-timeout-seconds=300"
})
@AutoConfigureWebTestClient
class RealOllamaChatSlowE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Small, instruction-tuned, good at short JSON; overridable for a stronger local model. */
    private static final String MODEL =
            System.getenv().getOrDefault("SLOW_LLM_E2E_MODEL", "qwen2.5:1.5b");
    private static final String IMAGE =
            System.getenv().getOrDefault("SLOW_LLM_E2E_IMAGE", "ollama/ollama:0.5.4");

    @Container
    static final OllamaContainer OLLAMA =
            new OllamaContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("ollama/ollama"));

    @DynamicPropertySource
    static void wireGatewayAtContainer(DynamicPropertyRegistry r) {
        r.add("llm.base-url", () -> OLLAMA.getEndpoint() + "/v1");
        r.add("llm.default-model", () -> MODEL);
        r.add("llm.fast-model", () -> MODEL);
    }

    @BeforeAll
    static void pullModel() throws Exception {
        // Blocks until the blob is local; the container is already up (Testcontainers started it).
        var result = OLLAMA.execInContainer("ollama", "pull", MODEL);
        assertThat(result.getExitCode())
                .withFailMessage("`ollama pull %s` failed: %s", MODEL, result.getStderr())
                .isZero();
    }

    @Autowired
    WebTestClient http;

    @Test
    void realCompletionParsesThroughTheOpenAiCompatibleProvider() {
        // A crisp JSON instruction + temperature 0 → a parseable object, deterministically.
        var request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system("You reply with ONLY a JSON object, no prose, no code fences."),
                LlmMessage.user("Return {\"ok\": true} exactly.")), 0.0);

        LlmChatResponse resp = http.post().uri("/v1/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody(LlmChatResponse.class)
                .returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        // Provider parse contract (all faked by MockProvider — here they come from a real completion):
        assertThat(resp.content()).isNotBlank();
        assertThat(resp.model()).isEqualTo(MODEL);            // Ollama echoes the requested tag.
        assertThat(resp.finishReason()).isNotBlank();          // a real "stop"/"length", not a stub.
        assertThat(resp.usage()).isNotNull();
        assertThat(resp.usage().promptTokens()).isPositive();
        assertThat(resp.usage().completionTokens()).isPositive();
        assertThat(resp.usage().totalTokens())
                .isEqualTo(resp.usage().promptTokens() + resp.usage().completionTokens());

        // JSON-shape drift guard: the real model, asked for JSON, produced a parseable object.
        assertThat(parseFirstJsonObject(resp.content()))
                .withFailMessage("expected a JSON object in model output, got: %s", resp.content())
                .isNotNull();
    }

    @Test
    void realStreamAccumulatesDeltaContent() {
        var request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.user("Say the single word: pong")));

        List<String> chunks = http.post().uri("/v1/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block(Duration.ofSeconds(300));

        // The SSE delta.content extraction path yielded real, non-empty text.
        assertThat(chunks).isNotNull();
        assertThat(String.join("", chunks)).isNotBlank();
    }

    /** Tolerant extraction — small models may wrap JSON in prose or ```json fences. */
    private static JsonNode parseFirstJsonObject(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(content.substring(start, end + 1));
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }
}
