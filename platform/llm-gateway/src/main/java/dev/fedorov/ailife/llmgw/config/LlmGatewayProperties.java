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

    /**
     * The smaller model the DEFAULT channel falls back to while the {@code coder-active} profile
     * is on (LC-4, {@code plans/lifecycle.md}). Both tags come from env on purpose — someone
     * else's pair will be different models entirely, so the mechanism must not name any.
     */
    private String defaultModelDownshift;

    private String fastModel;
    private String visionModel;
    private String embeddingModel = "mock-embed";

    /** Model-profile switching (LC-4). Off by default — see {@link ModelProfile}. */
    private ModelProfile modelProfile = new ModelProfile();

    /**
     * Runtime override of the DEFAULT-channel model, set by the model-profile switch and
     * {@code null} whenever the configured value applies. Deliberately the *only* mutable state
     * on this bean: model resolution already lives here ({@link #channelModels()}), so overriding
     * it here means every provider follows without knowing the mechanism exists. Volatile because
     * the switch happens on one request thread and is read by all the others.
     */
    private volatile String modelOverride;

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

    public String defaultModelDownshift() { return defaultModelDownshift; }
    public ModelProfile modelProfile() { return modelProfile; }

    /** The DEFAULT-channel model in force right now — the override if one is applied. */
    public String effectiveDefaultModel() {
        String override = modelOverride;
        return override != null ? override : defaultModel;
    }

    /**
     * Switch the DEFAULT channel to {@code model}, or back to the configured tag with
     * {@code null}. Called only by the model-profile switch, after it has confirmed the outgoing
     * model actually left the engine.
     */
    public void applyModelOverride(String model) { this.modelOverride = model; }

    public Map<LlmChannel, String> channelModels() {
        // Resolved per call, never cached, so a profile switch takes effect on the next request.
        String active = effectiveDefaultModel();
        Map<LlmChannel, String> map = new EnumMap<>(LlmChannel.class);
        map.put(LlmChannel.DEFAULT, active);
        // A channel with no model of its own follows the DEFAULT one — including through a
        // downshift, which is the point: those channels chose "whatever the big model is".
        map.put(LlmChannel.FAST, fastModel != null ? fastModel : active);
        map.put(LlmChannel.VISION, visionModel != null ? visionModel : active);
        map.put(LlmChannel.EMBEDDING, embeddingModel);
        return map;
    }

    /**
     * The two-tenant model swap (LC-4). ai-life shares one Mac + one Ollama with the coder
     * contour, and the GPU working set tops out near 48 GB — so while the coder works, ai-life
     * steps down to a smaller chat model.
     *
     * <p><b>Off by default, and that is a design commitment, not a default value:</b> the whole
     * dance is an add-on. With the flag off the endpoint does not exist, this gateway serves
     * {@code LLM_DEFAULT_MODEL} forever, and neither project depends on the other.
     */
    public static class ModelProfile {

        private boolean enabled = false;

        /**
         * How long to wait for the outgoing model to actually leave Ollama before giving up.
         * Unloading a 32B is not instant; exceeding this <b>fails the switch loudly</b> rather
         * than proceeding into a load that would put both models resident at once.
         */
        private int evictTimeoutSeconds = 120;

        /** How often to re-ask Ollama whether the outgoing model is gone. */
        private int pollIntervalMillis = 500;

        public boolean enabled() { return enabled; }
        public int evictTimeoutSeconds() { return evictTimeoutSeconds; }
        public int pollIntervalMillis() { return pollIntervalMillis; }

        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public void setEvictTimeoutSeconds(int evictTimeoutSeconds) { this.evictTimeoutSeconds = evictTimeoutSeconds; }
        public void setPollIntervalMillis(int pollIntervalMillis) { this.pollIntervalMillis = pollIntervalMillis; }
    }

    public void setProvider(String provider) { this.provider = provider; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
    public void setDefaultModelDownshift(String defaultModelDownshift) { this.defaultModelDownshift = defaultModelDownshift; }
    public void setModelProfile(ModelProfile modelProfile) { this.modelProfile = modelProfile; }
    public void setFastModel(String fastModel) { this.fastModel = fastModel; }
    public void setVisionModel(String visionModel) { this.visionModel = visionModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public void setAnthropicVersion(String anthropicVersion) { this.anthropicVersion = anthropicVersion; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) { this.requestTimeoutSeconds = requestTimeoutSeconds; }
    public void setSuppressThinking(boolean suppressThinking) { this.suppressThinking = suppressThinking; }
}
