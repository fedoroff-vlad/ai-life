package dev.fedorov.ailife.agents.finance.http;

import dev.fedorov.ailife.contracts.finance.GiftBudgetResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the household's gift-spending envelope from mcp-finance's non-MCP REST
 * passthrough ({@code GET /internal/gift-budget}, PR93). Used by the
 * {@code get_gift_budget} inter-agent action (Stage 4 / Track D, D2b) so the
 * budget-aware gift-recommender coordinator can gather the budget.
 *
 * <p>Error policy mirrors {@link BudgetStatusClient}:
 * <ul>
 *   <li>200 → {@code Optional.of(result)}.</li>
 *   <li>404 → {@code Optional.empty()} — no Gifts category / no active monthly
 *       budget. The caller treats this as "no budget set", not an error.</li>
 *   <li>5xx / network / timeout → propagated error; the action wraps it in a
 *       structured {@code ok=false} result.</li>
 * </ul>
 */
@Component
public class GiftBudgetClient {

    private final WebClient http;

    public GiftBudgetClient(@Qualifier("mcpFinanceWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<Optional<GiftBudgetResult>> fetch(UUID householdId) {
        return http.get()
                .uri(uri -> uri.path("/internal/gift-budget")
                        .queryParam("householdId", householdId)
                        .build())
                .retrieve()
                .bodyToMono(GiftBudgetResult.class)
                .timeout(Duration.ofSeconds(2))
                .map(Optional::of)
                .onErrorResume(WebClientResponseException.NotFound.class,
                        e -> Mono.just(Optional.empty()));
    }
}
