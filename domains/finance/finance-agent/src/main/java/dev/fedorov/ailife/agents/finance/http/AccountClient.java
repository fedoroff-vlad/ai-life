package dev.fedorov.ailife.agents.finance.http;

import dev.fedorov.ailife.contracts.finance.FinAccountDto;
import dev.fedorov.ailife.contracts.finance.UpsertAccountInput;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Accounts passthrough against mcp-finance. {@link #list} reads a household's accounts
 * ({@code GET /internal/accounts}) — used by {@code ReceiptParser} to resolve a target account; {@link
 * #upsert} creates/updates one ({@code POST /internal/account}) — used by {@code AccountManager}
 * (ADR-0002 slice 4b) to persist a chat-planned account under the household the {@code SharingResolver}
 * already routed to. Mirrors {@link CategoryClient} (list + upsert).
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

    public Mono<FinAccountDto> upsert(UpsertAccountInput input) {
        return http.post()
                .uri("/internal/account")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(FinAccountDto.class)
                .timeout(Duration.ofSeconds(5));
    }
}
