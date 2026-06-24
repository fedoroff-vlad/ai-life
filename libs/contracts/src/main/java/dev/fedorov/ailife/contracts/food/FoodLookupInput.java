package dev.fedorov.ailife.contracts.food;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request body for the {@code mcp-food-data} {@code POST /internal/food-lookup} passthrough (mirrors
 * the {@code food_lookup} tool args). {@code query} is either a numeric <b>barcode</b> (EAN/UPC) —
 * the precise match — or a free-text <b>product name</b> (best-effort first hit). The passthrough is
 * the deterministic, MockWebServer-testable path an agent calls (MCP/SSE can't be MockWebServer'd).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FoodLookupInput(
        String query) {
}
