package dev.fedorov.ailife.agents.finance.http;

import dev.fedorov.ailife.contracts.market.Quote;
import dev.fedorov.ailife.contracts.market.QuoteInput;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Calls the shared {@code mcp-market-data} capability's {@code POST /internal/quote} passthrough
 * (MD-a) to read a latest quote for one symbol. The {@code investment-advisor} flow uses this to
 * gather a quote per symbol the user named before the LLM synthesis — read-only data, advisory only.
 *
 * <p>The capability is also bound over MCP/SSE (for future LLM-driven tool selection), but this
 * deterministic call — the agent already knows the symbol it wants — goes over the HTTP passthrough,
 * which (unlike MCP/SSE) is MockWebServer-testable. Same shape as {@link CaptionClient}. Short
 * timeout: it's one Coordinator gather step and must not stall the reply.
 */
@Component
public class MarketDataClient {

    private final WebClient http;

    public MarketDataClient(@Qualifier("mcpMarketDataWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<Quote> quote(String symbol) {
        return http.post()
                .uri("/internal/quote")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new QuoteInput(symbol))
                .retrieve()
                .bodyToMono(Quote.class)
                .timeout(Duration.ofSeconds(8));
    }
}
