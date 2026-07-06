package dev.fedorov.ailife.mcp.chartrender.engine;

import dev.fedorov.ailife.contracts.chart.ChartSpec;

/**
 * Pluggable chart-rendering backend. The default is {@link Java2dChartEngine} (a pure
 * {@code Graphics2D} renderer — no external charting dependency, deterministic, no cost). Kept behind
 * an interface (mirrors {@code mcp-image-gen}'s {@code ImageEngine} / {@code mcp-market-data}'s
 * {@code MarketDataSource}) so a library-backed renderer could replace it via config with no caller
 * change. Synchronous (the MCP {@code @Tool} is blocking by convention; the {@code /internal}
 * passthrough runs it off the event loop).
 */
public interface ChartEngine {

    /**
     * @param spec the data-only chart description.
     * @return the rendered image bytes + mime type + the engine id that produced it.
     */
    RenderedChart render(ChartSpec spec);

    record RenderedChart(byte[] bytes, String mimeType, String engine) {
    }
}
