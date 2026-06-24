package dev.fedorov.ailife.mcp.fooddata.tools;

import dev.fedorov.ailife.contracts.food.FoodFacts;
import dev.fedorov.ailife.mcp.fooddata.engine.FoodDataSource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * The shared food-data toolbox. {@code food_lookup} (FD-a) reads nutrition facts for one product
 * from the configured {@link FoodDataSource} (Open Food Facts by default) — by barcode (precise) or
 * by product name (best-effort first hit). The capability returns reference numbers only — no LLM,
 * no decision. Any agent binds this server over MCP/SSE; the deterministic path goes through the
 * {@code /internal/food-lookup} HTTP passthrough. Read-only reference data.
 */
@Component
public class FoodDataMcpTools {

    private final FoodDataSource source;

    public FoodDataMcpTools(FoodDataSource source) {
        this.source = source;
    }

    @Tool(description = """
            Look up nutrition facts (calories + protein/fat/carbs per 100 g/ml) for one food product
            from Open Food Facts. Pass either a numeric barcode (EAN/UPC — the precise match) or a
            product name (best-effort first hit). Returns the product name, brand, package quantity,
            the a–e nutri-score, and the per-100g macros; name and the macro fields are null when the
            source has no product for the query. This is read-only reference DATA for analysis.
            """)
    public FoodFacts foodLookup(String query) {
        if (query == null || query.isBlank()) {
            return new FoodFacts(query, null, null, null, null, null, null, null, null, null);
        }
        return source.lookup(query).block();
    }
}
