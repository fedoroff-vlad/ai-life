package dev.fedorov.ailife.mcp.chartrender.tools;

import dev.fedorov.ailife.contracts.chart.ChartInput;
import dev.fedorov.ailife.contracts.chart.ChartResult;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.mcp.chartrender.engine.ChartEngine;
import dev.fedorov.ailife.mcp.chartrender.media.MediaUploader;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * The shared chart-render toolbox. {@code render_chart} turns a data-only {@code ChartSpec} into a PNG
 * via the configured {@link ChartEngine} (Java2D now — config swap, no caller change), stores it in
 * media-service, and returns the {@link ChartResult} (the media id + the engine). Any agent binds this
 * over MCP/SSE; the deterministic path goes through {@code /internal/render}.
 */
@Component
public class ChartRenderMcpTools {

    private final ChartEngine engine;
    private final MediaUploader media;

    public ChartRenderMcpTools(ChartEngine engine, MediaUploader media) {
        this.engine = engine;
        this.media = media;
    }

    @Tool(description = """
            Render a chart from data and store it in media-service; returns the stored media id (embed
            it like any other media object — e.g. inline in a Telegram report) plus the engine. Pass
            `householdId` (and optional `ownerId`) to scope the stored image, and a `spec` describing
            the chart: `type` ('bar' | 'line' | 'pie'), `title`, `categories` (x-axis / slice labels),
            `series` (one or more named series whose `values` align 1:1 with categories; pie uses the
            first), and optional `unit` (e.g. '₽'). Keep category labels short. The renderer is
            configured server-side — the caller is engine-agnostic.
            """)
    public ChartResult renderChart(ChartInput input) {
        if (input == null || input.householdId() == null) {
            throw new IllegalArgumentException("Missing required field: householdId");
        }
        if (input.spec() == null) {
            throw new IllegalArgumentException("Missing required field: spec");
        }
        ChartEngine.RenderedChart chart = engine.render(input.spec());
        MediaObjectDto stored = media.upload(
                input.householdId(), input.ownerId(), chart.mimeType(), chart.bytes()).block();
        return new ChartResult(stored == null ? null : stored.id(), chart.engine());
    }
}
