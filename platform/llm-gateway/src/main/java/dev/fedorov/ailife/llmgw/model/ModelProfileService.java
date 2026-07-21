package dev.fedorov.ailife.llmgw.model;

import dev.fedorov.ailife.llmgw.config.LlmGatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime model-profile switching for the shared Mac (LC-4, {@code plans/lifecycle.md} §B).
 *
 * <p>ai-life and the separate coder contour share one machine and one Ollama, and macOS caps the
 * GPU working set near 48 GB. Holding ai-life's 32B and the coder's 30B at the same time is over
 * that ceiling, so while the coder works this gateway serves a smaller chat model.
 *
 * <p><b>The ordering is the contract, and it is the opposite of the intuitive one.</b> A switch
 * <em>evicts before it loads</em>: unload the outgoing model, then <em>wait until Ollama actually
 * reports it gone</em>, and only then let anything pull the incoming one. Firing the unload and
 * moving on would leave both resident during the transition — momentarily ~38 GB of models, i.e.
 * precisely the swap/OOM this mechanism exists to prevent. An eviction that does not complete in
 * time therefore <b>fails the switch loudly</b> rather than proceeding.
 *
 * <p>The caller is the coder contour (coding-agent's {@code lifecycle.py}), which treats any
 * non-2xx — including the 404 you get with this feature disabled — as a refusal and declines to
 * load its own model. That is why a failure here must never answer 2xx.
 *
 * <p>Unloading a model is an Ollama-native operation with no OpenAI-dialect equivalent, so this is
 * the one place that talks to Ollama's own API. Its root URL is {@code LLM_BASE_URL} minus the
 * {@code /v1} suffix the OpenAI dialect requires.
 */
@Service
@ConditionalOnProperty(name = "llm.model-profile.enabled", havingValue = "true")
public class ModelProfileService {

    /** ai-life on its full-size chat model — the resting state. */
    public static final String NORMAL = "normal";
    /** The coder contour is working; ai-life steps down to {@code LLM_DEFAULT_MODEL_DOWNSHIFT}. */
    public static final String CODER_ACTIVE = "coder-active";

    private static final Logger log = LoggerFactory.getLogger(ModelProfileService.class);

    private final LlmGatewayProperties props;
    private final WebClient ollama;
    private final Duration evictTimeout;
    private final Duration pollInterval;

    private final AtomicReference<String> activeProfile = new AtomicReference<>(NORMAL);
    /** One switch at a time: two concurrent swaps would race each other's eviction. */
    private final AtomicBoolean switching = new AtomicBoolean();

    public ModelProfileService(LlmGatewayProperties props, WebClient.Builder builder) {
        if (props.baseUrl() == null || props.baseUrl().isBlank()) {
            throw new IllegalStateException(
                    "llm.model-profile.enabled=true requires LLM_BASE_URL — the profile switch "
                            + "unloads models through Ollama's own API");
        }
        if (props.defaultModelDownshift() == null || props.defaultModelDownshift().isBlank()) {
            throw new IllegalStateException(
                    "llm.model-profile.enabled=true requires LLM_DEFAULT_MODEL_DOWNSHIFT — the "
                            + "profile has no model to step down to (no tag is hardcoded)");
        }
        this.props = props;
        this.evictTimeout = Duration.ofSeconds(props.modelProfile().evictTimeoutSeconds());
        this.pollInterval = Duration.ofMillis(props.modelProfile().pollIntervalMillis());
        this.ollama = builder.clone().baseUrl(ollamaRoot(props.baseUrl())).build();
    }

    /** {@code http://ollama:11434/v1} → {@code http://ollama:11434}: /api/* is not under /v1. */
    static String ollamaRoot(String baseUrl) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return trimmed.endsWith("/v1") ? trimmed.substring(0, trimmed.length() - "/v1".length()) : trimmed;
    }

    public String activeProfile() {
        return activeProfile.get();
    }

    /** The DEFAULT-channel model in force right now — what the profile actually bought us. */
    public String activeModel() {
        return props.effectiveDefaultModel();
    }

    /**
     * Move to {@code profile}, completing only once the swap is really done.
     *
     * <p>Idempotent: re-signalling the profile already in force is a no-op, so a caller that
     * restarts and re-announces itself does not evict a model for nothing.
     */
    public Mono<Void> switchTo(String profile) {
        // Deferred so every state read and the in-flight guard happen on subscribe, not on
        // assembly — otherwise a Mono nobody subscribed to would leave the guard latched.
        return Mono.defer(() -> doSwitch(profile));
    }

    private Mono<Void> doSwitch(String profile) {
        if (!NORMAL.equals(profile) && !CODER_ACTIVE.equals(profile)) {
            return Mono.error(new UnknownProfileException(profile));
        }
        if (profile.equals(activeProfile.get())) {
            return Mono.empty();
        }
        String outgoing = props.effectiveDefaultModel();
        String incoming = CODER_ACTIVE.equals(profile) ? props.defaultModelDownshift() : props.defaultModel();
        if (outgoing.equals(incoming)) {
            // Configured to the same tag both ways: nothing to evict, so just record the profile.
            activeProfile.set(profile);
            return Mono.empty();
        }
        if (!switching.compareAndSet(false, true)) {
            return Mono.error(new SwitchInProgressException());
        }
        log.info("model profile {} -> {}: evicting {} before loading {}",
                activeProfile.get(), profile, outgoing, incoming);
        return evict(outgoing)
                .then(Mono.fromRunnable(() -> adopt(profile, incoming)))
                .then(warm(incoming))
                .doFinally(signal -> switching.set(false))
                .then();
    }

    /**
     * Point the DEFAULT channel at the incoming model.
     *
     * <p>Between the confirmed eviction and this line, a chat request would still resolve to the
     * model we just unloaded and make Ollama load it straight back — so this happens immediately
     * after the eviction and <em>before</em> the warm-up, not after it.
     */
    private void adopt(String profile, String incoming) {
        props.applyModelOverride(CODER_ACTIVE.equals(profile) ? incoming : null);
        activeProfile.set(profile);
    }

    /** Unload {@code model} and do not complete until Ollama stops reporting it as loaded. */
    private Mono<Void> evict(String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        // Ollama's unload: keep_alive 0 with no prompt. It answers before the memory is actually
        // released, which is exactly why the poll below is not optional.
        body.put("keep_alive", 0);
        return ollama.post().uri("/api/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .then(awaitGone(model));
    }

    private Mono<Void> awaitGone(String model) {
        return resident(model)
                .filter(resident -> !resident)
                .repeatWhenEmpty(repeat -> repeat.delayElements(pollInterval))
                .timeout(evictTimeout)
                .onErrorMap(TimeoutException.class, e -> new EvictionTimeoutException(model, evictTimeout))
                .doOnSuccess(ignored -> log.info("model {} confirmed gone from Ollama", model))
                .then();
    }

    private Mono<Boolean> resident(String model) {
        return ollama.get().uri("/api/ps")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> {
                    for (JsonNode entry : json.path("models")) {
                        // Ollama reports the tag as `model`; older builds used `name`.
                        if (model.equals(entry.path("model").asText(""))
                                || model.equals(entry.path("name").asText(""))) {
                            return true;
                        }
                    }
                    return false;
                });
    }

    /**
     * Pre-load the incoming model so the next user message doesn't pay for it.
     *
     * <p>Soft-fails on purpose: by this point the outgoing model is gone and the override is
     * applied, so the box is under budget and correct — a failed warm-up costs latency once, and
     * failing the whole switch over it would be a worse answer than a slow first reply.
     */
    private Mono<Void> warm(String model) {
        return ollama.post().uri("/api/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", model))
                .retrieve()
                .bodyToMono(String.class)
                .doOnError(e -> log.warn("could not pre-load {}: {}", model, e.toString()))
                .onErrorComplete()
                .then();
    }

    /** The switch could not be completed — the caller must treat this as "did not happen". */
    public static class ModelSwitchException extends RuntimeException {
        public ModelSwitchException(String message) {
            super(message);
        }
    }

    public static class EvictionTimeoutException extends ModelSwitchException {
        public EvictionTimeoutException(String model, Duration waited) {
            super("model '" + model + "' was still resident in Ollama after " + waited.toSeconds()
                    + "s — refusing to switch, because loading the incoming model on top of it "
                    + "would exceed the GPU budget");
        }
    }

    public static class SwitchInProgressException extends ModelSwitchException {
        public SwitchInProgressException() {
            super("another model-profile switch is in flight — retry once it settles");
        }
    }

    public static class UnknownProfileException extends ModelSwitchException {
        public UnknownProfileException(String profile) {
            super("unknown profile '" + profile + "' (expected '" + NORMAL + "' or '" + CODER_ACTIVE + "')");
        }
    }
}
