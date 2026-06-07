package dev.fedorov.ailife.agents.finance.http;

import dev.fedorov.ailife.contracts.finance.FinTransactionDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads a single transaction row from mcp-finance's
 * {@code GET /internal/transaction/{id}}. Used by {@code TriggerController} to
 * enrich a scheduler-driven {@code transaction.uncategorised} payload before
 * the LLM call. Error policy mirrors the other internal clients (200 → present,
 * 404 → empty, 5xx → propagated; the controller maps that to 503).
 */
@Component
public class TransactionClient {

    private final WebClient http;

    public TransactionClient(@Qualifier("mcpFinanceWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<Optional<FinTransactionDto>> fetch(UUID transactionId) {
        return http.get()
                .uri("/internal/transaction/{id}", transactionId)
                .retrieve()
                .bodyToMono(FinTransactionDto.class)
                .timeout(Duration.ofSeconds(2))
                .map(Optional::of)
                .onErrorResume(WebClientResponseException.NotFound.class,
                        e -> Mono.just(Optional.empty()));
    }
}
