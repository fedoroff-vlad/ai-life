package dev.fedorov.ailife.mcp.imagegen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the image-gen capability. {@code engine} selects the backend behind the
 * {@code ImageEngine} interface: {@code stub} (default — a placeholder PNG, no model, no cost) or
 * {@code local} (a self-hosted model server, e.g. on a Mac Studio, at {@code localUrl}). Swapping the
 * engine is a config change only — no caller change (the owner will flip to {@code local} once the
 * GPU model is up). {@code mediaServiceUrl} is where generated images are stored.
 */
@ConfigurationProperties(prefix = "image-gen")
public class McpImageGenProperties {

    /** Which engine to wire: {@code stub} (default) or {@code local}. */
    private String engine = "stub";

    /** Base URL of the self-hosted model server used when {@code engine=local} (POST /generate). */
    private String localUrl = "http://localhost:9200";

    /** media-service base URL — generated images are uploaded here and referenced by media id. */
    private String mediaServiceUrl = "http://media-service:8088";

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public String getLocalUrl() { return localUrl; }
    public void setLocalUrl(String localUrl) { this.localUrl = localUrl; }

    public String getMediaServiceUrl() { return mediaServiceUrl; }
    public void setMediaServiceUrl(String mediaServiceUrl) { this.mediaServiceUrl = mediaServiceUrl; }
}
