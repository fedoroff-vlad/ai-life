package dev.fedorov.ailife.agents.finance.http;

import dev.fedorov.ailife.contracts.finance.FinAccountDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Lists a household's accounts via mcp-finance's {@code GET /internal/accounts}. Used by
 * {@code ReceiptParser} to resolve a target account for a parsed receipt transaction.
 */
@Component
public class AccountClient {

    private final WebClient http;

    public AccountClient(@Qualifier("mcpFinanceWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<List<FinAccountDto>> list(UUID householdId) {
        return http.get()
                .uri(uri -> uri.path("/internal/accounts")
                        .queryParam("householdId", householdId)
                        .build())
                .retrieve()
                .bodyToFlux(FinAccountDto.class)
                .collectList()
                .timeout(Duration.ofSeconds(2));
    }
}
