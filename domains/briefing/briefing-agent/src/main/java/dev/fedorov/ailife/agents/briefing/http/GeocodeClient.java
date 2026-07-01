package dev.fedorov.ailife.agents.briefing.http;

import dev.fedorov.ailife.contracts.weather.GeoLocation;
import dev.fedorov.ailife.contracts.weather.GeocodeInput;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Calls the {@code mcp-weather} capability's {@code POST /internal/geocode} passthrough to resolve a
 * stated city name into coordinates + timezone. The briefing-profiler uses it once, at profile-set
 * time, so the stored profile carries lat/lon (which the digest's weather gather needs) rather than a
 * raw city string. Soft-fails to an empty Mono so a geocoding hiccup never sinks the profile write —
 * the profile is saved without coordinates and can be corrected later.
 */
@Component
public class GeocodeClient {

    private final WebClient http;

    public GeocodeClient(@Qualifier("mcpWeatherWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<GeoLocation> geocode(String name, String language) {
        return http.post()
                .uri("/internal/geocode")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new GeocodeInput(name, language))
                .retrieve()
                .bodyToMono(GeoLocation.class)
                .timeout(Duration.ofSeconds(10))
                .onErrorResume(e -> Mono.empty());
    }
}
