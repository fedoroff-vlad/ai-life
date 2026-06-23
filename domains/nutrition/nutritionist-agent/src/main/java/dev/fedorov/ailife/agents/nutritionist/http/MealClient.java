package dev.fedorov.ailife.agents.nutritionist.http;

import dev.fedorov.ailife.contracts.nutrition.LogMealInput;
import dev.fedorov.ailife.contracts.nutrition.MealLogDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Calls the {@code mcp-nutrition} domain-MCP's {@code POST /internal/meal} passthrough (NU-c1) to
 * persist a logged meal. The food-log flow has already extracted a concrete {@link LogMealInput}
 * (from a photo caption or typed text), so it writes deterministically over HTTP rather than through
 * an LLM-driven MCP tool call (the MCP/SSE binding stays for future tool selection but isn't
 * MockWebServer-testable). Same shape as stylist's {@code WardrobeClient}.
 */
@Component
public class MealClient {

    private final WebClient http;

    public MealClient(@Qualifier("mcpNutritionWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<MealLogDto> log(LogMealInput input) {
        return http.post()
                .uri("/internal/meal")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(MealLogDto.class)
                .timeout(Duration.ofSeconds(10));
    }
}
