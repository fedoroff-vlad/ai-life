package dev.fedorov.ailife.agentruntime.http;

import dev.fedorov.ailife.agentruntime.config.AgentRuntimeProperties;
import dev.fedorov.ailife.contracts.memory.PersonRelationsResponse;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.contracts.memory.RecallMemoryRequest;
import dev.fedorov.ailife.contracts.message.MessageReceivedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Shared memory-service client. Lifted from calendar-agent's PR17 + finance-agent's
 * PR24c — the implementations were byte-identical except for the package, so this
 * is now the canonical version.
 *
 * <p>Strict no-throw contract: any error (network, 5xx, 500 ms timeout, missing
 * required field) collapses to an empty {@link List} / a
 * {@link PersonRelationsResponse} with empty arrays. Memory is enrichment — the
 * skill MUST still run if memory-service is down.
 */
public class MemoryClient {

    private static final Logger log = LoggerFactory.getLogger(MemoryClient.class);
    private static final Duration TIMEOUT = Duration.ofMillis(500);
    private static final ParameterizedTypeReference<List<RecallMemoryHit>> HIT_LIST =
            new ParameterizedTypeReference<>() {};

    private final WebClient http;
    private final AgentRuntimeProperties props;

    public MemoryClient(@Qualifier("memoryServiceWebClient") WebClient http,
                        AgentRuntimeProperties props) {
        this.http = http;
        this.props = props;
    }

    public Mono<List<RecallMemoryHit>> recall(UUID householdId,
                                              UUID userId,
                                              UUID personId,
                                              String query) {
        if (householdId == null || query == null || query.isBlank()) {
            return Mono.just(List.of());
        }
        RecallMemoryRequest req = new RecallMemoryRequest(
                householdId, userId, personId, query, props.getMemoryRecallK());
        return http.post()
                .uri("/v1/memories/recall")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .retrieve()
                .bodyToMono(HIT_LIST)
                .timeout(TIMEOUT)
                .onErrorResume(e -> {
                    log.warn("memory recall failed for household={} query={}: {}",
                            householdId, query, e.toString());
                    return Mono.just(List.of());
                });
    }

    public Mono<PersonRelationsResponse> personRelations(UUID householdId, UUID personId) {
        if (householdId == null || personId == null) {
            return Mono.just(emptyRelations(personId));
        }
        return http.get()
                .uri(uri -> uri.path("/v1/graph/person/{id}/relations")
                        .queryParam("householdId", householdId)
                        .build(personId))
                .retrieve()
                .bodyToMono(PersonRelationsResponse.class)
                .timeout(TIMEOUT)
                .onErrorResume(e -> {
                    log.warn("memory person-relations failed for household={} person={}: {}",
                            householdId, personId, e.toString());
                    return Mono.just(emptyRelations(personId));
                });
    }

    /**
     * Fire-and-forget: drop an observation at memory-service's durable
     * {@code POST /v1/observations} so it can learn durable facts from what this
     * agent saw (memory-from-chat / MFC-c). An agent is a capture producer for the
     * surfaces the orchestrator can't see — e.g. finance-agent emits a receipt
     * caption that arrives as an attachment-only message (which the orchestrator's
     * text capture skips by design).
     *
     * <p>Off the response path: never awaited, never affects the reply, any failure
     * swallowed (best-effort passive capture, same posture as {@link #recall}).
     * No-op on missing household or blank text.
     */
    public void observe(UUID householdId, UUID userId, String text, String source) {
        if (householdId == null || text == null || text.isBlank()) {
            return;
        }
        http.post()
                .uri("/v1/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MessageReceivedEvent(householdId, userId, text, source))
                .retrieve()
                .toBodilessEntity()
                .timeout(TIMEOUT)
                .onErrorResume(e -> {
                    log.warn("memory observe failed for household={}: {}", householdId, e.toString());
                    return Mono.empty();
                })
                .subscribe();
    }

    private static PersonRelationsResponse emptyRelations(UUID personId) {
        return new PersonRelationsResponse(personId, List.of(), List.of());
    }
}
