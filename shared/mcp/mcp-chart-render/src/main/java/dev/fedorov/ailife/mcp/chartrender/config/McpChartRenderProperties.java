package dev.fedorov.ailife.mcp.chartrender.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the chart-render capability. {@code engine} selects the backend behind the
 * {@code ChartEngine} interface: {@code java2d} (default — a pure {@code Graphics2D} renderer, no
 * external dependency). Kept swappable (mirrors {@code mcp-image-gen}'s engine selector) so a
 * library-backed renderer could replace it later via config with no caller change.
 * {@code mediaServiceUrl} is where rendered charts are stored.
 */
@ConfigurationProperties(prefix = "chart-render")
public class McpChartRenderProperties {

    /** Which renderer to wire: {@code java2d} (default). */
    private String engine = "java2d";

    /** media-service base URL — rendered charts are uploaded here and referenced by media id. */
    private String mediaServiceUrl = "http://media-service:8088";

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public String getMediaServiceUrl() { return mediaServiceUrl; }
    public void setMediaServiceUrl(String mediaServiceUrl) { this.mediaServiceUrl = mediaServiceUrl; }
}
