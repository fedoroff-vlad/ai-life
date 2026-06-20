package dev.fedorov.ailife.contracts.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request body for the {@code mcp-web} {@code POST /internal/fetch} passthrough (mirrors the
 * {@code fetch_url} tool arg): the URL of a page to read. The deterministic,
 * MockWebServer-testable path an agent calls (MCP/SSE can't be MockWebServer'd).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FetchUrlInput(
        String url) {
}
