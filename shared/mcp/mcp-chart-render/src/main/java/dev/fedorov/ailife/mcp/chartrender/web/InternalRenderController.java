package dev.fedorov.ailife.mcp.chartrender.web;

import dev.fedorov.ailife.contracts.chart.ChartInput;
import dev.fedorov.ailife.contracts.chart.ChartResult;
import dev.fedorov.ailife.mcp.chartrender.tools.ChartRenderMcpTools;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Non-MCP REST passthrough for {@code render_chart}. A capability-MCP is bound over MCP/SSE, but that
 * transport can't be MockWebServer'd, so an agent that already knows it wants a chart (deterministic —
 * it has the data) hits this HTTP path instead. Delegates straight to the {@code render_chart} tool.
 * Mirrors mcp-image-gen's {@code InternalGenerateController}.
 *
 * <p>The tool call is blocking ({@code .block()} per the MCP {@code @Tool} convention), so it runs on
 * {@link Schedulers#boundedElastic()} to keep the WebFlux event loop free.
 */
@RestController
@RequestMapping("/internal/render")
public class InternalRenderController {

    private final ChartRenderMcpTools tools;

    public InternalRenderController(ChartRenderMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public Mono<ChartResult> render(@RequestBody ChartInput input) {
        return Mono.fromCallable(() -> tools.renderChart(input))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
