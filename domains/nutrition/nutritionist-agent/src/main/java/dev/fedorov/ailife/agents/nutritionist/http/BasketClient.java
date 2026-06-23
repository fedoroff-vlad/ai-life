package dev.fedorov.ailife.agents.nutritionist.http;

import dev.fedorov.ailife.contracts.nutrition.BasketDto;
import dev.fedorov.ailife.contracts.nutrition.SaveBasketInput;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Calls the {@code mcp-nutrition} domain-MCP's {@code POST /internal/basket} passthrough (NU-f1) to
 * persist an analysed grocery basket. The basket-breakdown flow has already parsed the items and run
 * the КБЖУ + good/watch/cut breakdown, so it writes deterministically over HTTP rather than through
 * an LLM-driven MCP tool call (the MCP/SSE binding stays for future tool selection but isn't
 * MockWebServer-testable). Same shape as {@code MealClient}.
 */
@Component
public class BasketClient {

    private final WebClient http;

    public BasketClient(@Qualifier("mcpNutritionWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<BasketDto> save(SaveBasketInput input) {
        return http.post()
                .uri("/internal/basket")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(BasketDto.class)
                .timeout(Duration.ofSeconds(10));
    }
}
