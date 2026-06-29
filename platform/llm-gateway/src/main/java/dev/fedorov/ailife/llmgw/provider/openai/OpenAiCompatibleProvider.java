package dev.fedorov.ailife.llmgw.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmEmbedRequest;
import dev.fedorov.ailife.contracts.llm.LlmEmbedResponse;
import dev.fedorov.ailife.contracts.llm.LlmImage;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.llm.LlmRole;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.llmgw.config.LlmGatewayProperties;
import dev.fedorov.ailife.llmgw.provider.LlmProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions wire format ({@code POST /v1/chat/completions} +
 * {@code POST /v1/embeddings}). Active when {@code LLM_PROVIDER=openai-compatible}.
 *
 * <p>Covers every backend that speaks this dialect: local Ollama (free baseline for tests
 * and dev), DeepSeek cloud, Together, vLLM, and OpenAI proper. Ollama also serves the
 * embedding channel that {@link dev.fedorov.ailife.llmgw.provider.anthropic.AnthropicProvider}
 * can't.
 *
 * <p>{@code LLM_API_KEY} is optional — Ollama accepts requests without {@code Authorization}.
 * When the key is set, it goes through as {@code Authorization: Bearer <key>}.
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "openai-compatible")
public class OpenAiCompatibleProvider implements LlmProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SSE_DONE = "[DONE]";

    private final LlmGatewayProperties props;
    private final WebClient http;
    private final Duration timeout;

    public OpenAiCompatibleProvider(LlmGatewayProperties props, WebClient.Builder builder) {
        if (props.baseUrl() == null || props.baseUrl().isBlank()) {
            throw new IllegalStateException(
                    "LLM_PROVIDER=openai-compatible requires LLM_BASE_URL "
                            + "(e.g. http://ollama:11434/v1 or https://api.deepseek.com/v1)");
        }
        this.props = props;
        this.timeout = Duration.ofSeconds(props.requestTimeoutSeconds());
        WebClient.Builder b = builder.clone().baseUrl(props.baseUrl());
        if (props.apiKey() != null && !props.apiKey().isBlank()) {
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey());
        }
        this.http = b.build();
    }

    @Override
    public String id() {
        return "openai-compatible";
    }

    @Override
    public Mono<LlmChatResponse> chat(LlmChatRequest request) {
        Map<String, Object> body = buildChatBody(request, false);
        String model = (String) body.get("model");
        return http.post().uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .map(json -> parseChatResponse(json, model))
                .onErrorMap(WebClientResponseException.class, OpenAiCompatibleProvider::wrap);
    }

    @Override
    public Flux<String> chatStream(LlmChatRequest request) {
        Map<String, Object> body = buildChatBody(request, true);
        return http.post().uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .takeUntil(SSE_DONE::equals)
                .filter(line -> !SSE_DONE.equals(line))
                .mapNotNull(OpenAiCompatibleProvider::extractDelta)
                .filter(s -> !s.isEmpty())
                .onErrorMap(WebClientResponseException.class, OpenAiCompatibleProvider::wrap);
    }

    @Override
    public Mono<LlmEmbedResponse> embed(LlmChannel channel, LlmEmbedRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        String model = props.channelModels().get(LlmChannel.EMBEDDING);
        body.put("model", model);
        body.put("input", request.inputs());
        return http.post().uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .map(json -> parseEmbedResponse(json, model))
                .onErrorMap(WebClientResponseException.class, OpenAiCompatibleProvider::wrap);
    }

    private Map<String, Object> buildChatBody(LlmChatRequest request, boolean stream) {
        String model = props.channelModels().get(request.channel());
        List<Map<String, Object>> messages = new ArrayList<>(request.messages().size());
        for (LlmMessage m : request.messages()) {
            if (m.role() == LlmRole.TOOL) {
                throw new IllegalArgumentException(
                        "openai-compatible provider does not support role=tool yet");
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", m.role().wire());
            entry.put("content", m.hasImages() ? imageParts(m) : (m.content() == null ? "" : m.content()));
            messages.add(entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        }
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (stream) {
            body.put("stream", true);
        }
        return body;
    }

    /**
     * OpenAI vision shape: {@code content} becomes an array of parts — optional leading
     * {@code text} part, then one {@code image_url} part per attachment whose URL is a
     * {@code data:<media-type>;base64,<data>} URI. Text-only turns keep the plain-string form.
     */
    private static List<Map<String, Object>> imageParts(LlmMessage m) {
        List<Map<String, Object>> parts = new ArrayList<>();
        if (m.content() != null && !m.content().isBlank()) {
            parts.add(Map.of("type", "text", "text", m.content()));
        }
        for (LlmImage img : m.images()) {
            String url = "data:" + img.mediaType() + ";base64," + img.dataBase64();
            parts.add(Map.of("type", "image_url", "image_url", Map.of("url", url)));
        }
        return parts;
    }

    private LlmChatResponse parseChatResponse(JsonNode json, String fallbackModel) {
        JsonNode choice = json.path("choices").path(0);
        String content = choice.path("message").path("content").asText("");
        String finish = choice.path("finish_reason").asText("stop");
        JsonNode usage = json.path("usage");
        int prompt = usage.path("prompt_tokens").asInt(0);
        int completion = usage.path("completion_tokens").asInt(0);
        int total = usage.path("total_tokens").asInt(prompt + completion);
        return new LlmChatResponse(
                json.path("model").asText(fallbackModel),
                content,
                finish,
                new LlmUsage(prompt, completion, total));
    }

    private LlmEmbedResponse parseEmbedResponse(JsonNode json, String fallbackModel) {
        List<float[]> vectors = new ArrayList<>();
        JsonNode data = json.path("data");
        if (data.isArray()) {
            for (JsonNode entry : data) {
                JsonNode arr = entry.path("embedding");
                float[] v = new float[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    v[i] = (float) arr.get(i).asDouble();
                }
                vectors.add(v);
            }
        }
        JsonNode usage = json.path("usage");
        int prompt = usage.path("prompt_tokens").asInt(0);
        int total = usage.path("total_tokens").asInt(prompt);
        return new LlmEmbedResponse(
                json.path("model").asText(fallbackModel),
                vectors,
                new LlmUsage(prompt, 0, total));
    }

    /**
     * OpenAI SSE wire format: each event is {@code data: <json>\n\n}; stream terminates with
     * {@code data: [DONE]}. WebClient's SSE decoder hands us the bare payload, so we only
     * dig {@code choices[0].delta.content} out.
     */
    private static String extractDelta(String dataLine) {
        if (dataLine == null || dataLine.isEmpty()) return null;
        try {
            JsonNode json = MAPPER.readTree(dataLine);
            return json.path("choices").path(0).path("delta").path("content").asText("");
        } catch (Exception e) {
            return null;
        }
    }

    private static RuntimeException wrap(WebClientResponseException ex) {
        return new IllegalStateException(
                "openai-compatible API error " + ex.getStatusCode() + ": "
                        + ex.getResponseBodyAsString(),
                ex);
    }
}
