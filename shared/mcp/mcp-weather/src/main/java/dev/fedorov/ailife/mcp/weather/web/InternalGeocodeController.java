package dev.fedorov.ailife.mcp.weather.web;

import dev.fedorov.ailife.contracts.weather.GeoLocation;
import dev.fedorov.ailife.contracts.weather.GeocodeInput;
import dev.fedorov.ailife.mcp.weather.tools.WeatherMcpTools;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Non-MCP REST passthrough for {@code geocode}. Mirrors {@link InternalForecastController}: an agent
 * that already knows it wants to resolve a city name (deterministic) hits this HTTP path instead of
 * the MCP/SSE transport (which can't be MockWebServer'd). Delegates straight to the {@code geocode}
 * tool. The briefing-agent calls this at profile-set time to turn a stated city into coordinates.
 *
 * <p>The tool call is blocking ({@code .block()} per the MCP {@code @Tool} convention), so it runs on
 * {@link Schedulers#boundedElastic()} to keep the WebFlux event loop free.
 */
@RestController
@RequestMapping("/internal/geocode")
public class InternalGeocodeController {

    private final WeatherMcpTools tools;

    public InternalGeocodeController(WeatherMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public Mono<GeoLocation> geocode(@RequestBody GeocodeInput input) {
        return Mono.fromCallable(() -> tools.geocode(input.name(), input.language()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
