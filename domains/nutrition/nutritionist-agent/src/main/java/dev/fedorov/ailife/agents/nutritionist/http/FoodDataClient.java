package dev.fedorov.ailife.agents.nutritionist.http;

import dev.fedorov.ailife.contracts.food.FoodFacts;
import dev.fedorov.ailife.contracts.food.FoodLookupInput;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Calls the shared {@code mcp-food-data} capability's {@code POST /internal/food-lookup} passthrough
 * (FD-a) to read nutrition facts (per-100g КБЖУ) for one product — by barcode (precise) or product
 * name (best-effort first hit). The basket-breakdown flow uses this to ground its КБЖУ in real
 * reference data instead of relying only on the LLM's estimate; a no-match returns a
 * {@link FoodFacts} with a null {@code name} (not an error).
 *
 * <p>The capability is also bound over MCP/SSE (for future LLM-driven tool selection), but this
 * deterministic call — the agent already knows the product it wants — goes over the HTTP passthrough,
 * which (unlike MCP/SSE) is MockWebServer-testable. Same shape as {@link CaptionClient} /
 * finance-agent's {@code MarketDataClient}. Short timeout: it's one enrichment step and must not
 * stall the reply.
 */
@Component
public class FoodDataClient {

    private final WebClient http;

    public FoodDataClient(@Qualifier("mcpFoodDataWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<FoodFacts> lookup(String query) {
        return http.post()
                .uri("/internal/food-lookup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new FoodLookupInput(query))
                .retrieve()
                .bodyToMono(FoodFacts.class)
                .timeout(Duration.ofSeconds(8));
    }
}
