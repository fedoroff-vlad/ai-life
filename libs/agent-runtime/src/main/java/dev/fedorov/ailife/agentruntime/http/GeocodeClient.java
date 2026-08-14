package dev.fedorov.ailife.agentruntime.http;

import dev.fedorov.ailife.contracts.weather.GeoLocation;
import dev.fedorov.ailife.contracts.weather.GeocodeInput;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Shared client for the {@code mcp-weather} capability's {@code POST /internal/geocode} passthrough:
 * resolve a stated city name into coordinates + timezone. Callers use it once, at profile-set time, so a
 * stored profile carries lat/lon (which downstream weather/climate gathers need) rather than a raw city
 * string. Soft-fails to an empty {@link Mono} so a geocoding hiccup never sinks the profile write — the
 * profile is saved without coordinates and can be corrected later.
 *
 * <p>Lives in {@code agent-runtime} because more than one agent geocodes a profile's home base (briefing,
 * travel): a capability HTTP client is shared code, not a per-agent copy. Opt-in — a consuming agent
 * declares the {@code @Bean} in its {@code OutboundHttpConfig}, passing a {@code WebClient} pointed at
 * {@code mcp-weather}.
 */
public class GeocodeClient {

    private final WebClient http;

    public GeocodeClient(WebClient http) {
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
