package dev.fedorov.ailife.orchestrator.memory;

import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.contracts.memory.RecallMemoryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Reactive client over memory-service's {@code POST /v1/memories/recall}.
 *
 * <p>Strict no-throw contract: any error (disabled, no household, network, 5xx,
 * timeout) collapses to {@link List#of()}. Memory is a "nice to have" before
 * intent classification — we never block routing on it.
 */
@Component
public class MemoryClient {

    private static final Logger log = LoggerFactory.getLogger(MemoryClient.class);
    private static final Duration TIMEOUT = Duration.ofMillis(500);
    private static final ParameterizedTypeReference<List<RecallMemoryHit>> HIT_LIST =
            new ParameterizedTypeReference<>() {};

    private final WebClient http;
    private final MemoryProperties props;

    public MemoryClient(WebClient.Builder builder, MemoryProperties props) {
        this.http = builder.clone().baseUrl(props.getUrl()).build();
        this.props = props;
    }

    /**
     * Recall top-{@link MemoryProperties#getRecallK()} memories for the given
     * household + (optional) user, embedded from {@code query}. Empty list on
     * disabled, missing household, or any failure.
     */
    public Mono<List<RecallMemoryHit>> recall(UUID householdId, UUID userId, String query) {
        if (!props.isEnabled()) {
            return Mono.just(List.of());
        }
        if (householdId == null || query == null || query.isBlank()) {
            return Mono.just(List.of());
        }
        RecallMemoryRequest req = new RecallMemoryRequest(
                householdId, userId, null, query, props.getRecallK());
        return http.post()
                .uri("/v1/memories/recall")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .retrieve()
                .bodyToMono(HIT_LIST)
                .timeout(TIMEOUT)
                .onErrorResume(e -> {
                    log.warn("memory recall failed for household={}: {}", householdId, e.toString());
                    return Mono.just(List.of());
                });
    }
}
