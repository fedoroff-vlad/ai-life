package dev.fedorov.ailife.agents.briefing.http;

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
 * Reads a spend-by-category snapshot from mcp-finance's {@code GET /internal/spending-by-category}
 * passthrough — the same deterministic gather path finance-agent's {@code financial-advisor} uses. The
 * digest's finance gather step calls it for yesterday's window (a morning briefing reports what was
 * spent the previous day). A short timeout — it's one Coordinator gather step and must not stall the
 * digest. Mirrors finance-agent's {@code SpendingClient}.
 */
@Component
public class FinanceSnapshotClient {

    private final WebClient http;

    public FinanceSnapshotClient(@Qualifier("mcpFinanceWebClient") WebClient http) {
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
