package dev.fedorov.ailife.agents.stylist.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound HTTP destinations the stylist talks to. {@code mcpWardrobeUrl} is its own data
 * (the wardrobe domain-MCP); {@code mcpMediaProcessingUrl} + {@code mcpWebUrl} are shared
 * capabilities (vision caption / web trends) the deterministic flows (ST-c..e) call over their
 * {@code /internal/*} passthroughs. The profile / notifier / memory URLs back the shared
 * {@code agent-runtime} clients every agent imports.
 */
@ConfigurationProperties(prefix = "stylist-agent")
public class StylistAgentProperties {

    private String mcpWardrobeUrl = "http://mcp-wardrobe:8101";
    private String mcpMediaProcessingUrl = "http://mcp-media-processing:8097";
    private String mcpWebUrl = "http://mcp-web:8098";
    private String profileServiceUrl = "http://profile-service:8082";
    private String notifierUrl = "http://notifier-service:8084";
    private String memoryServiceUrl = "http://memory-service:8087";

    public String getMcpWardrobeUrl() { return mcpWardrobeUrl; }
    public void setMcpWardrobeUrl(String mcpWardrobeUrl) { this.mcpWardrobeUrl = mcpWardrobeUrl; }

    public String getMcpMediaProcessingUrl() { return mcpMediaProcessingUrl; }
    public void setMcpMediaProcessingUrl(String mcpMediaProcessingUrl) {
        this.mcpMediaProcessingUrl = mcpMediaProcessingUrl;
    }

    public String getMcpWebUrl() { return mcpWebUrl; }
    public void setMcpWebUrl(String mcpWebUrl) { this.mcpWebUrl = mcpWebUrl; }

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
