package dev.fedorov.ailife.contracts.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request body for the {@code mcp-web} {@code POST /internal/search} passthrough (mirrors the
 * {@code web_search} tool args). {@code limit} is the max number of hits to return; null →
 * the server default. The passthrough is the deterministic, MockWebServer-testable path an
 * agent calls (MCP/SSE can't be MockWebServer'd).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebSearchInput(
        String query,
        Integer limit) {
}
