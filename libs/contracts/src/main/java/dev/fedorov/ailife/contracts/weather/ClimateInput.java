package dev.fedorov.ailife.contracts.weather;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request body for the {@code mcp-weather} {@code POST /internal/climate} passthrough (mirrors the
 * {@code climate} tool args). {@code latitude}/{@code longitude} are decimal degrees; {@code month}
 * is an optional 1–12 filter — when set, only that month's {@link MonthlyNormal} is returned,
 * otherwise all twelve. The passthrough is the deterministic, MockWebServer-testable path an agent
 * calls (MCP/SSE can't be mocked).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClimateInput(
        Double latitude,
        Double longitude,
        Integer month) {
}
