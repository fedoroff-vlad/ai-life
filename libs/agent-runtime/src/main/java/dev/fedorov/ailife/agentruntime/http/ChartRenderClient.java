package dev.fedorov.ailife.agentruntime.http;

import dev.fedorov.ailife.contracts.chart.ChartInput;
import dev.fedorov.ailife.contracts.chart.ChartResult;
import dev.fedorov.ailife.contracts.chart.ChartSpec;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Shared client for the {@code mcp-chart-render} capability's {@code POST /internal/render} passthrough
 * (#292): turn a {@link ChartSpec} into a stored PNG and get back its {@link ChartResult#mediaId()}. The
 * capability is also bound over MCP/SSE (for future LLM-driven tool selection), but this deterministic
 * call — the agent already has the data it wants to plot — goes over the HTTP passthrough, which (unlike
 * MCP/SSE) is MockWebServer-testable. A render + media upload is quick but not instant, so a 10s timeout.
 *
 * <p>Lives in {@code agent-runtime} because more than one agent renders charts (finance reports, the
 * travel board): a capability HTTP client is shared code, not a per-agent copy. Opt-in — a consuming
 * agent declares the {@code @Bean} in its {@code OutboundHttpConfig}, passing a {@code WebClient} pointed
 * at {@code mcp-chart-render}.
 */
public class ChartRenderClient {

    private final WebClient http;

    public ChartRenderClient(WebClient http) {
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
