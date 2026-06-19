package dev.fedorov.ailife.mcp.mediaprocessing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mediaprocessing")
public class McpMediaProcessingProperties {

    /** media-service base URL — where {@code ocr} fetches blob bytes by object id. */
    private String mediaServiceUrl = "http://media-service:8088";

    public String getMediaServiceUrl() { return mediaServiceUrl; }
    public void setMediaServiceUrl(String mediaServiceUrl) { this.mediaServiceUrl = mediaServiceUrl; }
}
