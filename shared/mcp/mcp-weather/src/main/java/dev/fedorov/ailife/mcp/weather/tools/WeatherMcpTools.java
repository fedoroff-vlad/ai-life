package dev.fedorov.ailife.mcp.weather.tools;

import dev.fedorov.ailife.contracts.weather.GeoLocation;
import dev.fedorov.ailife.contracts.weather.Weather;
import dev.fedorov.ailife.mcp.weather.engine.WeatherSource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * The shared weather toolbox. {@code forecast} (BR-a) reads today's forecast for one location from
 * the configured {@link WeatherSource} (Open-Meteo by default). The capability returns numbers + a
 * condition label only — no LLM, no narrative. Any agent binds this server over MCP/SSE; the
 * deterministic path goes through the {@code /internal/forecast} HTTP passthrough.
 */
@Component
public class WeatherMcpTools {

    private final WeatherSource source;

    public WeatherMcpTools(WeatherSource source) {
        this.source = source;
    }

    @Tool(description = """
            Get today's weather forecast for one location. Pass latitude and longitude in decimal
            degrees (resolving a place name to coordinates is your job). Returns the day's high/low
            in °C, the max precipitation probability (0–100), the max wind speed (km/h), a WMO
            weather code and a human condition label, and the forecast date. Fields are null when the
            source has no data. This is read-only forecast DATA — composing a briefing from it is your job.
            """)
    public Weather forecast(double latitude, double longitude) {
        return source.forecast(latitude, longitude).block();
    }

    @Tool(description = """
            Resolve a stated place or city name to coordinates. Pass the name (e.g. 'Москва', 'Berlin')
            and an optional ISO-639 language hint for the canonical name. Returns the best match's
            latitude/longitude (feed them to `forecast`), canonical name, country, and IANA timezone;
            fields are null when no match is found. Use this to turn a user's stated city into the
            coordinates the weather forecast needs.
            """)
    public GeoLocation geocode(String name, String language) {
        if (name == null || name.isBlank()) {
            return new GeoLocation(null, null, null, null, null);
        }
        return source.geocode(name, language).block();
    }
}
