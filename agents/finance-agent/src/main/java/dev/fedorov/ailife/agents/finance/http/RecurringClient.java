package dev.fedorov.ailife.agents.finance.http;

import dev.fedorov.ailife.contracts.finance.FinRecurringDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads a single recurring template from mcp-finance's
 * {@code GET /internal/recurring/{id}}. Used by {@code TriggerController} to
 * enrich a scheduler-driven {@code recurring.due} payload before the LLM
 * call.
 *
 * <p>Error policy mirrors {@link BudgetStatusClient}: 200 → present, 404 →
 * empty Optional (deleted upstream — skill emits SKIP), 5xx / network /
 * timeout → propagated so the controller returns 503 and scheduler retries.
 */
@Component
public class RecurringClient {

    private final WebClient http;

    public RecurringClient(@Qualifier("mcpFinanceWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<Optional<FinRecurringDto>> fetch(UUID recurringId) {
        return http.get()
                .uri("/internal/recurring/{id}", recurringId)
                .retrieve()
                .bodyToMono(FinRecurringDto.class)
                .timeout(Duration.ofSeconds(2))
                .map(Optional::of)
                .onErrorResume(WebClientResponseException.NotFound.class,
                        e -> Mono.just(Optional.empty()));
    }

    /**
     * Post-tick advance: ask mcp-finance to recompute and persist {@code
     * next_due} on the row. Errors are intentionally swallowed by the caller —
     * a stale {@code next_due} is a cosmetic UI problem, not a reason to fail
     * the trigger (which already ran).
     */
    public Mono<Void> advance(UUID recurringId) {
        return http.post()
                .uri("/internal/recurring/{id}/advance", recurringId)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(2))
                .then();
    }
}
