package dev.fedorov.ailife.contracts.weather;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request body for the {@code mcp-weather} {@code POST /internal/geocode} passthrough (mirrors the
 * {@code geocode} tool args). {@code name} is a stated place/city name (e.g. "Москва", "Berlin");
 * {@code language} is an optional ISO-639 hint for the returned canonical name (defaults to the
 * source default). The passthrough is the deterministic, MockWebServer-testable path an agent calls.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeocodeInput(
        String name,
        String language) {
}
