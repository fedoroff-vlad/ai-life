package dev.fedorov.ailife.mcp.fooddata.web;

import dev.fedorov.ailife.contracts.food.FoodFacts;
import dev.fedorov.ailife.contracts.food.FoodLookupInput;
import dev.fedorov.ailife.mcp.fooddata.tools.FoodDataMcpTools;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Non-MCP REST passthrough for {@code food_lookup}. A capability-MCP is bound over MCP/SSE, but that
 * transport can't be MockWebServer'd, so a caller that already knows it wants a food lookup
 * (deterministic — it has the barcode/name) hits this HTTP path instead. Delegates straight to the
 * {@code food_lookup} tool. Mirrors {@code mcp-market-data}'s {@code InternalQuoteController}. The
 * MCP {@code @Tool} stays the entry point for future LLM-driven tool selection.
 *
 * <p>The tool call is blocking ({@code .block()} per the MCP {@code @Tool} convention), so it runs
 * on {@link Schedulers#boundedElastic()} to keep the WebFlux event loop free.
 */
@RestController
@RequestMapping("/internal/food-lookup")
public class InternalFoodLookupController {

    private final FoodDataMcpTools tools;

    public InternalFoodLookupController(FoodDataMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public Mono<FoodFacts> lookup(@RequestBody FoodLookupInput input) {
        return Mono.fromCallable(() -> tools.foodLookup(input.query()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
