package dev.fedorov.ailife.agents.travel.http;

import dev.fedorov.ailife.contracts.weather.ClimateInput;
import dev.fedorov.ailife.contracts.weather.ClimateNormals;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Calls the {@code mcp-weather} capability's {@code POST /internal/climate} passthrough (TR-a) to fetch a
 * destination's monthly temperature/precipitation normals — the <b>season substrate</b> the TR-d planner
 * uses to judge whether a destination fits the requested month. Called once per trip plan for the
 * candidate destination's coordinates (resolved via {@link GeocodeClient}); soft-fails to an empty Mono so
 * an upstream hiccup, or a trip with no named destination, simply drops the climate gather step rather
 * than sinking the plan. Mirrors the profiler's {@link GeocodeClient} against the same capability.
 */
@Component
public class ClimateClient {

    private final WebClient http;

    public ClimateClient(@Qualifier("mcpWeatherWebClient") WebClient http) {
        this.http = http;
    }

    /** {@code month} is optional (1-12) — null asks for all twelve monthly normals. */
    public Mono<ClimateNormals> climate(double latitude, double longitude, Integer month) {
        return http.post()
                .uri("/internal/climate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ClimateInput(latitude, longitude, month))
                .retrieve()
                .bodyToMono(ClimateNormals.class)
                .timeout(Duration.ofSeconds(10))
                .onErrorResume(e -> Mono.empty());
    }
}
