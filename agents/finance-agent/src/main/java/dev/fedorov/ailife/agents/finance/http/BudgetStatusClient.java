package dev.fedorov.ailife.agents.finance.http;

import dev.fedorov.ailife.contracts.finance.BudgetStatusResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the current budget snapshot from mcp-finance's non-MCP REST passthrough
 * ({@code GET /internal/budget-status}). Used by {@code TriggerController} to
 * enrich a scheduler-driven {@code budget.alert} payload with live numbers
 * before the LLM call.
 *
 * <p>Error policy is differentiated:
 * <ul>
 *   <li>200 → {@code Optional.of(result)}.</li>
 *   <li>404 → {@code Optional.empty()} — no active budget for the slot. The
 *       caller treats this as "no-op / fall-through to SKIP-like 202" rather
 *       than retrying.</li>
 *   <li>5xx / network / timeout → propagated error. The caller maps this to
 *       a 5xx response so scheduler-service retries on the next tick.</li>
 * </ul>
 *
 * <p>Why a per-status policy: PR13's calendar {@code IcsImportClient} could
 * collapse everything to a single channel because {@code ics.pull} is
 * fire-and-forget. Budget alerts must distinguish "no budget" (don't fire)
 * from "mcp-finance down" (try again later) — those have different downstream
 * consequences.
 */
@Component
public class BudgetStatusClient {

    private final WebClient http;

    public BudgetStatusClient(@Qualifier("mcpFinanceWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<Optional<BudgetStatusResult>> fetch(UUID householdId, UUID categoryId, String period) {
        return http.get()
                .uri(uri -> uri.path("/internal/budget-status")
                        .queryParam("householdId", householdId)
                        .queryParam("categoryId", categoryId)
                        .queryParam("period", period)
                        .build())
                .retrieve()
                .bodyToMono(BudgetStatusResult.class)
                .timeout(Duration.ofSeconds(2))
                .map(Optional::of)
                .onErrorResume(WebClientResponseException.NotFound.class,
                        e -> Mono.just(Optional.empty()));
    }
}
