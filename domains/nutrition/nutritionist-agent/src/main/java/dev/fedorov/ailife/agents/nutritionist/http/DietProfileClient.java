package dev.fedorov.ailife.agents.nutritionist.http;

import dev.fedorov.ailife.contracts.nutrition.DietProfileDto;
import dev.fedorov.ailife.contracts.nutrition.SetDietProfileInput;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Calls the {@code mcp-nutrition} domain-MCP's {@code POST /internal/diet-profile} passthrough
 * (NU-d1) to upsert a person's diet profile. The diet-profiler flow has already extracted a concrete
 * {@link SetDietProfileInput} from typed goals/restrictions, so it writes deterministically over HTTP
 * rather than through an LLM-driven MCP tool call (the MCP/SSE binding stays for future selection but
 * isn't MockWebServer-testable). Same shape as {@code MealClient}.
 */
@Component
public class DietProfileClient {

    private final WebClient http;

    public DietProfileClient(@Qualifier("mcpNutritionWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<DietProfileDto> set(SetDietProfileInput input) {
        return http.post()
                .uri("/internal/diet-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(DietProfileDto.class)
                .timeout(Duration.ofSeconds(10));
    }
}
