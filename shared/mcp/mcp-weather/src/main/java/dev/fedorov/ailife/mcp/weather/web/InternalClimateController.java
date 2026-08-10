package dev.fedorov.ailife.mcp.weather.web;

import dev.fedorov.ailife.contracts.weather.ClimateInput;
import dev.fedorov.ailife.contracts.weather.ClimateNormals;
import dev.fedorov.ailife.mcp.weather.tools.WeatherMcpTools;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * Non-MCP REST passthrough for {@code climate} (TR-a). Mirrors {@link InternalGeocodeController}: an
 * agent that already knows it wants the monthly normals for a resolved point hits this HTTP path
 * instead of the MCP/SSE transport (which can't be MockWebServer'd). Delegates straight to the
 * {@code climate} tool. The travel-agent calls this for its candidate destinations' season fit.
 *
 * <p>The tool call is blocking ({@code .block()} per the MCP {@code @Tool} convention), so it runs on
 * {@link Schedulers#boundedElastic()} to keep the WebFlux event loop free. Missing coordinates yield
 * empty normals (not an error), matching the tool's own soft-fail contract.
 */
@RestController
@RequestMapping("/internal/climate")
public class InternalClimateController {

    private final WeatherMcpTools tools;

    public InternalClimateController(WeatherMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public Mono<ClimateNormals> climate(@RequestBody ClimateInput input) {
        if (input.latitude() == null || input.longitude() == null) {
            return Mono.just(new ClimateNormals(input.latitude(), input.longitude(), List.of()));
        }
        return Mono.fromCallable(() ->
                        tools.climate(input.latitude(), input.longitude(), input.month()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
