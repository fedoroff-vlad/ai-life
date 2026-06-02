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

    public String provider() { return provider; }
    public String baseUrl() { return baseUrl; }
    public String apiKey() { return apiKey; }
    public String defaultModel() { return defaultModel; }

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
}
