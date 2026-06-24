package dev.fedorov.ailife.agents.creator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound HTTP destinations the creator talks to. {@code mcpCreatorUrl} is its own data (the creator
 * domain-MCP); {@code mcpWebUrl} / {@code mcpYoutubeUrl} / {@code mcpRedditUrl} / {@code mcpFeedsUrl}
 * are the shared trend-source capabilities the deterministic gather (CR-d) calls over their
 * {@code /internal/*} passthroughs; {@code mediaServiceUrl} stores the rendered content-plan board and
 * {@code publicMediaBaseUrl} is what its link is built from. The profile / notifier / memory URLs back
 * the shared {@code agent-runtime} clients every agent imports.
 */
@ConfigurationProperties(prefix = "creator-agent")
public class CreatorAgentProperties {

    private String mcpCreatorUrl = "http://mcp-creator:8108";
    private String mcpWebUrl = "http://mcp-web:8098";
    private String mcpYoutubeUrl = "http://mcp-youtube:8110";
    private String mcpRedditUrl = "http://mcp-reddit:8111";
    private String mcpFeedsUrl = "http://mcp-feeds:8112";
    private String mediaServiceUrl = "http://media-service:8088";
    private String profileServiceUrl = "http://profile-service:8082";
    private String notifierUrl = "http://notifier-service:8084";
    private String memoryServiceUrl = "http://memory-service:8087";

    /**
     * Public base URL a stored deliverable link is built from (so the user can open it on any device).
     * Defaults to the internal media-service URL; set to a publicly-reachable gateway base in a real
     * deployment. The link is {@code <base>/v1/media/{id}}.
     */
    private String publicMediaBaseUrl = "http://media-service:8088";

    public String getMcpCreatorUrl() { return mcpCreatorUrl; }
    public void setMcpCreatorUrl(String mcpCreatorUrl) { this.mcpCreatorUrl = mcpCreatorUrl; }

    public String getMcpWebUrl() { return mcpWebUrl; }
    public void setMcpWebUrl(String mcpWebUrl) { this.mcpWebUrl = mcpWebUrl; }

    public String getMcpYoutubeUrl() { return mcpYoutubeUrl; }
    public void setMcpYoutubeUrl(String mcpYoutubeUrl) { this.mcpYoutubeUrl = mcpYoutubeUrl; }

    public String getMcpRedditUrl() { return mcpRedditUrl; }
    public void setMcpRedditUrl(String mcpRedditUrl) { this.mcpRedditUrl = mcpRedditUrl; }

    public String getMcpFeedsUrl() { return mcpFeedsUrl; }
    public void setMcpFeedsUrl(String mcpFeedsUrl) { this.mcpFeedsUrl = mcpFeedsUrl; }

    public String getMediaServiceUrl() { return mediaServiceUrl; }
    public void setMediaServiceUrl(String mediaServiceUrl) { this.mediaServiceUrl = mediaServiceUrl; }

    public String getPublicMediaBaseUrl() { return publicMediaBaseUrl; }
    public void setPublicMediaBaseUrl(String publicMediaBaseUrl) {
        this.publicMediaBaseUrl = publicMediaBaseUrl;
    }

    public String getProfileServiceUrl() { return profileServiceUrl; }
    public void setProfileServiceUrl(String profileServiceUrl) {
        this.profileServiceUrl = profileServiceUrl;
    }

    public String getNotifierUrl() { return notifierUrl; }
    public void setNotifierUrl(String notifierUrl) { this.notifierUrl = notifierUrl; }

    public String getMemoryServiceUrl() { return memoryServiceUrl; }
    public void setMemoryServiceUrl(String memoryServiceUrl) {
        this.memoryServiceUrl = memoryServiceUrl;
    }
}
