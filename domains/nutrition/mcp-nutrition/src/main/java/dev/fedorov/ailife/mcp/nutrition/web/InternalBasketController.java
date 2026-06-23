package dev.fedorov.ailife.mcp.nutrition.web;

import dev.fedorov.ailife.contracts.nutrition.SaveBasketInput;
import dev.fedorov.ailife.mcp.nutrition.tools.NutritionMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Non-MCP REST passthrough for saving an analysed grocery basket. An agent that has already computed
 * a concrete {@link SaveBasketInput} — nutritionist-agent's basket-breakdown flow (NU-f), which
 * parsed the items from a basket photo/list and ran the КБЖУ + good/watch/cut breakdown — persists
 * it deterministically over HTTP rather than through an LLM-driven MCP tool call (the MCP/SSE
 * transport can't be MockWebServer'd). It delegates straight to {@link NutritionMcpTools#saveBasket}
 * so the tool's invariants apply identically; the MCP {@code @Tool} stays the entry point for any
 * future LLM-driven tool selection. Mirrors {@code InternalMealController} (NU-c1).
 */
@RestController
@RequestMapping("/internal/basket")
public class InternalBasketController {

    private final NutritionMcpTools tools;

    public InternalBasketController(NutritionMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public ResponseEntity<?> save(@RequestBody SaveBasketInput input) {
        try {
            return ResponseEntity.ok(tools.saveBasket(input));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
