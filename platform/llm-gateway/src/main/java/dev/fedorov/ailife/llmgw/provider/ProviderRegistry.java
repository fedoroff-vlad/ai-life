package dev.fedorov.ailife.llmgw.provider;

import dev.fedorov.ailife.llmgw.config.LlmGatewayProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves the active {@link LlmProvider} based on {@link LlmGatewayProperties#provider()}.
 * Fails fast at startup if the configured provider isn't on the classpath.
 */
@Component
public class ProviderRegistry {

    private final LlmProvider active;

    public ProviderRegistry(List<LlmProvider> providers, LlmGatewayProperties props) {
        Map<String, LlmProvider> byId = providers.stream()
                .collect(Collectors.toUnmodifiableMap(LlmProvider::id, Function.identity()));
        LlmProvider chosen = byId.get(props.provider());
        if (chosen == null) {
            throw new IllegalStateException(
                    "No LLM provider registered for LLM_PROVIDER=" + props.provider()
                            + ". Available: " + byId.keySet());
        }
        this.active = chosen;
    }

    public LlmProvider active() {
        return active;
    }
}
