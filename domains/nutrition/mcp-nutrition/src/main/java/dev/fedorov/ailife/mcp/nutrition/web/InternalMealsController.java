package dev.fedorov.ailife.mcp.nutrition.web;

import dev.fedorov.ailife.contracts.nutrition.MealLogDto;
import dev.fedorov.ailife.mcp.nutrition.tools.NutritionMcpTools;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Non-MCP REST passthrough for reading logged meals. Delegates to the {@code list_meals} tool (same
 * filters — {@code ownerId} scope + {@code limit}), so a system caller — nutritionist-agent's
 * nutrition-analysis flow (NU-e) gathering recent meals — reads over HTTP rather than the MCP/SSE
 * transport (which can't be MockWebServer'd). Mirrors mcp-wardrobe's {@code GET /internal/items}.
 */
@RestController
@RequestMapping("/internal/meals")
public class InternalMealsController {

    private final NutritionMcpTools tools;

    public InternalMealsController(NutritionMcpTools tools) {
        this.tools = tools;
    }

    @GetMapping
    public List<MealLogDto> list(@RequestParam UUID householdId,
                                 @RequestParam(required = false) UUID ownerId,
                                 @RequestParam(required = false) Integer limit) {
        return tools.listMeals(householdId, ownerId, limit);
    }
}
