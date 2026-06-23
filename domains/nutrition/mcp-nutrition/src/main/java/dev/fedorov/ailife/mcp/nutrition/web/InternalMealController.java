package dev.fedorov.ailife.mcp.nutrition.web;

import dev.fedorov.ailife.contracts.nutrition.LogMealInput;
import dev.fedorov.ailife.mcp.nutrition.tools.NutritionMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Non-MCP REST passthrough for logging a meal. A capability is bound over MCP/SSE, but that
 * transport can't be MockWebServer'd, so an agent that already has a concrete {@link LogMealInput}
 * (the food-log flow, which parsed it from a meal photo caption or typed text) hits this HTTP
 * passthrough instead. It delegates straight to {@link NutritionMcpTools#logMeal} so the tool's
 * invariants (required-field checks) apply identically; the MCP {@code @Tool} stays the entry point
 * for any future LLM-driven tool selection. Mirrors mcp-wardrobe's {@code InternalItemController}
 * (ST-c1). Used by nutritionist-agent's food-log flow (NU-c).
 */
@RestController
@RequestMapping("/internal/meal")
public class InternalMealController {

    private final NutritionMcpTools tools;

    public InternalMealController(NutritionMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public ResponseEntity<?> log(@RequestBody LogMealInput input) {
        try {
            return ResponseEntity.ok(tools.logMeal(input));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
