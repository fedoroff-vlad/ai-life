package dev.fedorov.ailife.agents.finance.http;

import dev.fedorov.ailife.contracts.finance.SpendingByCategoryRow;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reads the spend-by-category aggregate from mcp-finance's
 * {@code GET /internal/spending-by-category} passthrough. Used by the
 * {@code financial-advisor} flow to gather a spending snapshot (a recent window
 * + a prior window for trend comparison) before the LLM synthesis. A short
 * timeout — it's one Coordinator gather step and must not stall the reply.
 */
@Component
public class SpendingClient {

    private final WebClient http;

    public SpendingClient(@Qualifier("mcpFinanceWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<List<SpendingByCategoryRow>> spendingByCategory(UUID householdId, Instant from, Instant to) {
        return http.get()
                .uri(uri -> uri.path("/internal/spending-by-category")
                        .queryParam("householdId", householdId)
                        .queryParam("from", from.toString())
                        .queryParam("to", to.toString())
                        .build())
                .retrieve()
                .bodyToFlux(SpendingByCategoryRow.class)
                .collectList()
                .timeout(Duration.ofSeconds(5));
    }
}
