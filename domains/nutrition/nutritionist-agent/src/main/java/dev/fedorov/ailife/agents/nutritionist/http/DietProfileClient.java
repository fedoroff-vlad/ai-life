package dev.fedorov.ailife.agents.nutritionist.http;

import dev.fedorov.ailife.contracts.nutrition.DietProfileDto;
import dev.fedorov.ailife.contracts.nutrition.SetDietProfileInput;
import dev.fedorov.ailife.profile.PersonalizationProfileClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The nutrition domain's typed binding of the shared {@link PersonalizationProfileClient} (ADR-0005): the
 * {@code get(householdId, ownerId)} / {@code set(input)} shape over {@code mcp-nutrition}'s
 * {@code /internal/diet-profile} passthrough (NU-d1) is generic; only the path + DTO + set-input are
 * nutrition-specific. The extract→write flow lives in the {@code PersonalizationProfiler} template.
 * Nutrition's read path keeps its richer own + household-default gather (MealPlanner/MealReads); the shared
 * {@code ProfileScopeResolver} single-profile fallback is applied where a single per-member profile is read
 * ({@code NutritionAnalyst}).
 */
@Component
public class DietProfileClient
        extends PersonalizationProfileClient<DietProfileDto, SetDietProfileInput> {

    public DietProfileClient(@Qualifier("mcpNutritionWebClient") WebClient http) {
        super(http, "/internal/diet-profile", DietProfileDto.class);
    }
}
