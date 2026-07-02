package dev.fedorov.ailife.agents.briefing.http;

import dev.fedorov.ailife.contracts.weather.ForecastInput;
import dev.fedorov.ailife.contracts.weather.Weather;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Reads today's forecast from the shared {@code mcp-weather} capability's {@code POST /internal/forecast}
 * passthrough. The digest's weather gather step calls it with the coordinates the briefing-profiler
 * geocoded into the profile (BR-c). Deterministic HTTP (MCP/SSE can't be MockWebServer'd) — one
 * Coordinator gather step, so a short timeout keeps a slow source from stalling the whole digest.
 * Mirrors {@link GeocodeClient} (same capability, its other passthrough).
 */
@Component
public class ForecastClient {

    private final WebClient http;

    public ForecastClient(@Qualifier("mcpWeatherWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<Weather> forecast(double latitude, double longitude) {
        return http.post()
                .uri("/internal/forecast")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ForecastInput(latitude, longitude))
                .retrieve()
                .bodyToMono(Weather.class)
                .timeout(Duration.ofSeconds(8));
    }
}
