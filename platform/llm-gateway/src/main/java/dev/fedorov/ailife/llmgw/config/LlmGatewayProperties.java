package dev.fedorov.ailife.llmgw.config;

import dev.fedorov.ailife.contracts.llm.LlmChannel;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumMap;
import java.util.Map;

/**
 * Maps env vars (see plan §5.2) to typed config.
 * <p>
 * Single provider per gateway instance — chosen by {@code LLM_PROVIDER}. Per-channel model
 * names are resolved lazily by providers, with fallback to {@link #defaultModel()}.
 */
@ConfigurationProperties(prefix = "llm")
public class LlmGatewayProperties {

    /** Active provider id: {@code mock}, {@code anthropic}, {@code openai-compatible}. */
    private String provider = "mock";

    /** Base URL of the upstream API (ignored by {@code mock}). */
    private String baseUrl;

    /** API key for the upstream provider. */
    private String apiKey;

    /** Default model used by channels that don't have a specific override. */
    private String defaultModel = "mock-large";

    private String fastModel;
    private String visionModel;
    private String embeddingModel = "mock-embed";

    /** Anthropic API version pinned in the {@code anthropic-version} header. */
    private String anthropicVersion = "2023-06-01";

    /** Fallback for {@code max_tokens} when callers don't set one (Anthropic requires it). */
    private int maxTokens = 4096;

    /**
     * Upstream request timeout (seconds) for chat/embed calls. Default 60 suits cloud APIs;
     * a slow local model (CPU Ollama generating long structured output) can legitimately need
     * more — the Stage 5 golden tests bump this. Applies to every provider.
     */
    private int requestTimeoutSeconds = 60;

    /**
     * Suppress a "thinking" model's hidden reasoning pass on chat calls. When {@code true} the
     * openai-compatible provider sends {@code "reasoning_effort":"none"} in the request body —
     * the OpenAI reasoning-control field, which Ollama honours to disable a Qwen3-style thinking
     * pass. On CPU this is decisive: a routing call drops from ~144s to ~4s (hidden reasoning
     * tokens 448→8) and the reply stays clean JSON. NB the Qwen3 {@code /no_think} prompt tag does
     * NOT work through Ollama's {@code /v1} endpoint (it still generates the reasoning, just moves
     * it to a {@code reasoning} field) — the body field is the only thing that works. Off by
     * default; the Stage 5 golden lane and the FAST/routing channel on qwen3 turn it on via
     * {@code LLM_SUPPRESS_THINKING=true}.
     */
    private boolean suppressThinking = false;

    public String provider() { return provider; }
    public String baseUrl() { return baseUrl; }
    public String apiKey() { return apiKey; }
    public String defaultModel() { return defaultModel; }
    public String anthropicVersion() { return anthropicVersion; }
    public int maxTokens() { return maxTokens; }
    public int requestTimeoutSeconds() { return requestTimeoutSeconds; }
    public boolean suppressThinking() { return suppressThinking; }

    public Map<LlmChannel, String> channelModels() {
        Map<LlmChannel, String> map = new EnumMap<>(LlmChannel.class);
        map.put(LlmChannel.DEFAULT, defaultModel);
        map.put(LlmChannel.FAST, fastModel != null ? fastModel : defaultModel);
        map.put(LlmChannel.VISION, visionModel != null ? visionModel : defaultModel);
        map.put(LlmChannel.EMBEDDING, embeddingModel);
        return map;
    }

    public void setProvider(String provider) { this.provider = provider; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
    public void setFastModel(String fastModel) { this.fastModel = fastModel; }
    public void setVisionModel(String visionModel) { this.visionModel = visionModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public void setAnthropicVersion(String anthropicVersion) { this.anthropicVersion = anthropicVersion; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) { this.requestTimeoutSeconds = requestTimeoutSeconds; }
    public void setSuppressThinking(boolean suppressThinking) { this.suppressThinking = suppressThinking; }
}
