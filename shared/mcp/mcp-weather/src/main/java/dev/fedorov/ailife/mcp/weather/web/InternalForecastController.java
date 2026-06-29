package dev.fedorov.ailife.mcp.weather.web;

import dev.fedorov.ailife.contracts.weather.ForecastInput;
import dev.fedorov.ailife.contracts.weather.Weather;
import dev.fedorov.ailife.mcp.weather.tools.WeatherMcpTools;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Non-MCP REST passthrough for {@code forecast}. A capability-MCP is bound over MCP/SSE, but that
 * transport can't be MockWebServer'd, so a caller that already knows it wants a forecast
 * (deterministic — it has the coordinates) hits this HTTP path instead. Delegates straight to the
 * {@code forecast} tool. Mirrors {@code mcp-market-data}'s {@code InternalQuoteController}. The MCP
 * {@code @Tool} stays the entry point for future LLM-driven tool selection.
 *
 * <p>The tool call is blocking ({@code .block()} per the MCP {@code @Tool} convention), so it runs
 * on {@link Schedulers#boundedElastic()} to keep the WebFlux event loop free.
 */
@RestController
@RequestMapping("/internal/forecast")
public class InternalForecastController {

    private final WeatherMcpTools tools;

    public InternalForecastController(WeatherMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public Mono<Weather> forecast(@RequestBody ForecastInput input) {
        double lat = input.latitude() == null ? 0.0 : input.latitude();
        double lon = input.longitude() == null ? 0.0 : input.longitude();
        return Mono.fromCallable(() -> tools.forecast(lat, lon))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
