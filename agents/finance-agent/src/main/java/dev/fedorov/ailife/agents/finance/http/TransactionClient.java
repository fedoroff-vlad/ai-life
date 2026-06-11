package dev.fedorov.ailife.agents.finance.http;

import dev.fedorov.ailife.contracts.finance.AddTransactionInput;
import dev.fedorov.ailife.contracts.finance.FinTransactionDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Talks to mcp-finance's transaction passthroughs.
 * <ul>
 *   <li>{@link #fetch(UUID)} — {@code GET /internal/transaction/{id}}; used by
 *   {@code TriggerController} to enrich a scheduler-driven
 *   {@code transaction.uncategorised} payload (200 → present, 404 → empty, 5xx →
 *   propagated → controller maps to 503).</li>
 *   <li>{@link #add(AddTransactionInput)} — {@code POST /internal/transaction};
 *   used by {@code ReceiptParser} to persist a draft parsed from a photo.</li>
 * </ul>
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

    public Mono<FinTransactionDto> add(AddTransactionInput input) {
        return http.post()
                .uri("/internal/transaction")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(FinTransactionDto.class)
                .timeout(Duration.ofSeconds(3));
    }
}
