package dev.fedorov.ailife.agents.finance.http;

import dev.fedorov.ailife.contracts.chart.ChartInput;
import dev.fedorov.ailife.contracts.chart.ChartResult;
import dev.fedorov.ailife.contracts.chart.ChartSpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Calls the shared {@code mcp-chart-render} capability's {@code POST /internal/render} passthrough
 * (#292) to turn a {@link ChartSpec} into a stored PNG and get back its {@link ChartResult#mediaId()}.
 * {@code MonthlyReporter} uses this to embed a spending chart in the HTML report board.
 *
 * <p>The capability is also bound over MCP/SSE (for future LLM-driven tool selection), but this
 * deterministic call — the agent already has the data it wants to plot — goes over the HTTP
 * passthrough, which (unlike MCP/SSE) is MockWebServer-testable. Same shape as {@link MarketDataClient}.
 * A render + media upload is quick but not instant, so a 10s timeout.
 */
@Component
public class ChartRenderClient {

    private final WebClient http;

    public ChartRenderClient(@Qualifier("mcpChartRenderWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<ChartResult> render(UUID householdId, UUID ownerId, ChartSpec spec) {
        return http.post()
                .uri("/internal/render")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ChartInput(householdId, ownerId, spec))
                .retrieve()
                .bodyToMono(ChartResult.class)
                .timeout(Duration.ofSeconds(10));
    }
}
