package dev.fedorov.ailife.contracts.weather;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request body for the {@code mcp-weather} {@code POST /internal/forecast} passthrough (mirrors the
 * {@code forecast} tool args). {@code latitude}/{@code longitude} are decimal degrees — the calling
 * agent resolves them from its config/profile (the capability is location-agnostic and schema-less).
 * The passthrough is the deterministic, MockWebServer-testable path an agent calls (MCP/SSE can't be
 * MockWebServer'd).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ForecastInput(
        Double latitude,
        Double longitude) {
}
