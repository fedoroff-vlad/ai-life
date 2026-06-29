package dev.fedorov.ailife.llmgw.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
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
 * Anthropic Messages API ({@code POST /v1/messages}) behind the gateway's {@link LlmProvider} SPI.
 *
 * <p>Translation rules:
 * <ul>
 *   <li>SYSTEM messages are concatenated (blank-line separated) into Anthropic's top-level
 *       {@code system} field — Anthropic does not accept system entries inside {@code messages}.</li>
 *   <li>USER / ASSISTANT messages map straight to {@code messages[]}. TOOL is not used in
 *       ai-life today and rejected fast so a future regression doesn't silently drop a turn.</li>
 *   <li>Embeddings are unsupported — Anthropic ships no embedding endpoint. Callers should
 *       run a separate gateway instance with an embedding-capable provider for that channel.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "anthropic")
public class AnthropicProvider implements LlmProvider {

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

    private final LlmGatewayProperties props;
    private final WebClient http;
    private final Duration timeout;

    public AnthropicProvider(LlmGatewayProperties props, WebClient.Builder builder) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "LLM_PROVIDER=anthropic requires LLM_API_KEY to be set");
        }
        this.props = props;
        this.timeout = Duration.ofSeconds(props.requestTimeoutSeconds());
        String baseUrl = props.baseUrl() == null || props.baseUrl().isBlank()
                ? DEFAULT_BASE_URL
                : props.baseUrl();
        this.http = builder.clone()
                .baseUrl(baseUrl)
                .defaultHeader("x-api-key", props.apiKey())
                .defaultHeader("anthropic-version", props.anthropicVersion())
                .build();
    }

    @Override
    public String id() {
        return "anthropic";
    }

    @Override
    public Mono<LlmChatResponse> chat(LlmChatRequest request) {
        Map<String, Object> body = buildBody(request, false);
        String model = (String) body.get("model");
        return http.post().uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .map(json -> parseResponse(json, model))
                .onErrorMap(WebClientResponseException.class, AnthropicProvider::wrap);
    }

    @Override
    public Flux<String> chatStream(LlmChatRequest request) {
        Map<String, Object> body = buildBody(request, true);
        return http.post().uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .mapNotNull(AnthropicProvider::extractDelta)
                .filter(s -> !s.isEmpty())
                .onErrorMap(WebClientResponseException.class, AnthropicProvider::wrap);
    }

    @Override
    public Mono<LlmEmbedResponse> embed(LlmChannel channel, LlmEmbedRequest request) {
        return Mono.error(new UnsupportedOperationException(
                "Anthropic has no embeddings API — run llm-gateway with an embedding-capable "
                        + "provider for channel=embedding"));
    }

    private Map<String, Object> buildBody(LlmChatRequest request, boolean stream) {
        String model = props.channelModels().get(request.channel());
        StringBuilder system = new StringBuilder();
        List<Map<String, Object>> messages = new ArrayList<>(request.messages().size());
        for (LlmMessage m : request.messages()) {
            if (m.role() == LlmRole.SYSTEM) {
                if (m.content() == null) continue;
                if (system.length() > 0) system.append("\n\n");
                system.append(m.content());
            } else if (m.role() == LlmRole.USER || m.role() == LlmRole.ASSISTANT) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("role", m.role().wire());
                entry.put("content", m.hasImages() ? imageBlocks(m) : (m.content() == null ? "" : m.content()));
                messages.add(entry);
            } else {
                throw new IllegalArgumentException(
                        "Anthropic provider does not support role=" + m.role());
            }
        }
        if (messages.isEmpty()) {
            throw new IllegalArgumentException(
                    "Anthropic requires at least one user/assistant message");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        int maxTokens = request.maxTokens() != null ? request.maxTokens() : props.maxTokens();
        body.put("max_tokens", maxTokens);
        if (system.length() > 0) {
            body.put("system", system.toString());
        }
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (stream) {
            body.put("stream", true);
        }
        body.put("messages", messages);
        return body;
    }

    /**
     * Anthropic vision shape: a multimodal turn carries {@code content} as an array of blocks —
     * optional leading {@code text}, then one {@code image} block per attachment with a base64
     * {@code source}. Text-only turns keep the plain-string form (see caller).
     */
    private static List<Map<String, Object>> imageBlocks(LlmMessage m) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (m.content() != null && !m.content().isBlank()) {
            blocks.add(Map.of("type", "text", "text", m.content()));
        }
        for (LlmImage img : m.images()) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("type", "base64");
            source.put("media_type", img.mediaType());
            source.put("data", img.dataBase64());
            blocks.add(Map.of("type", "image", "source", source));
        }
        return blocks;
    }

    private LlmChatResponse parseResponse(JsonNode json, String model) {
        String content = "";
        JsonNode contentArr = json.get("content");
        if (contentArr != null && contentArr.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : contentArr) {
                if ("text".equals(block.path("type").asText())) {
                    sb.append(block.path("text").asText());
                }
            }
            content = sb.toString();
        }
        String stopReason = json.path("stop_reason").asText("stop");
        JsonNode usage = json.path("usage");
        int prompt = usage.path("input_tokens").asInt(0);
        int completion = usage.path("output_tokens").asInt(0);
        return new LlmChatResponse(
                json.path("model").asText(model),
                content,
                stopReason,
                new LlmUsage(prompt, completion, prompt + completion));
    }

    /**
     * Anthropic SSE delivers events as alternating {@code event: <type>} and {@code data: <json>}
     * lines. WebClient's default SSE decoder hands us each {@code data:} payload as a String;
     * we only care about {@code content_block_delta} → {@code text_delta.text}.
     */
    private static String extractDelta(String dataLine) {
        if (dataLine == null || dataLine.isEmpty()) return null;
        try {
            JsonNode json = MAPPER.readTree(dataLine);
            if (!"content_block_delta".equals(json.path("type").asText())) {
                return null;
            }
            JsonNode delta = json.path("delta");
            if (!"text_delta".equals(delta.path("type").asText())) {
                return null;
            }
            return delta.path("text").asText("");
        } catch (Exception e) {
            return null;
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static RuntimeException wrap(WebClientResponseException ex) {
        return new IllegalStateException(
                "Anthropic API error " + ex.getStatusCode() + ": " + ex.getResponseBodyAsString(),
                ex);
    }
}
