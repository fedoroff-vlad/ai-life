package dev.fedorov.ailife.agents.nutritionist.http;

import dev.fedorov.ailife.contracts.nutrition.MealLogDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Reads recent meals over {@code mcp-nutrition}'s {@code GET /internal/meals} passthrough (NU-e1).
 * The nutrition-analysis flow gathers the person's recent food log from here. Read-only; the
 * deterministic, MockWebServer-testable path (not the SSE transport). Mirrors stylist's
 * {@code WardrobeReadClient}.
 */
@Component
public class MealReadClient {

    private final WebClient http;

    public MealReadClient(@Qualifier("mcpNutritionWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<List<MealLogDto>> listMeals(UUID householdId, UUID ownerId, Integer limit) {
        return http.get()
                .uri(b -> {
                    b.path("/internal/meals").queryParam("householdId", householdId);
                    if (ownerId != null) b.queryParam("ownerId", ownerId);
                    if (limit != null) b.queryParam("limit", limit);
                    return b.build();
                })
                .retrieve()
                .bodyToFlux(MealLogDto.class)
                .collectList()
                .timeout(Duration.ofSeconds(10));
    }
}
