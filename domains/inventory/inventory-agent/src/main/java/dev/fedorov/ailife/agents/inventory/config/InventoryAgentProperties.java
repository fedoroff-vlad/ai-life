package dev.fedorov.ailife.agents.inventory.config;

import dev.fedorov.ailife.agentruntime.config.SharedClientProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound HTTP destinations the inventory agent talks to. {@code mcpInventoryUrl} is its own data
 * (the inventory domain-MCP — zones, containers, items); {@code mcpMediaProcessingUrl} is the shared
 * media capability whose {@code /internal/caption} passthrough names a photographed thing (IN-c). The
 * profile / notifier / memory URLs back the shared {@code agent-runtime} clients every agent imports.
 */
@ConfigurationProperties(prefix = "inventory-agent")
public class InventoryAgentProperties implements SharedClientProperties {

    private String mcpInventoryUrl = "http://mcp-inventory:8127";
    private String mcpMediaProcessingUrl = "http://mcp-media-processing:8097";
    private String profileServiceUrl = "http://profile-service:8082";
    private String notifierUrl = "http://notifier-service:8084";
    private String memoryServiceUrl = "http://memory-service:8087";

    public String getMcpInventoryUrl() { return mcpInventoryUrl; }
    public void setMcpInventoryUrl(String mcpInventoryUrl) { this.mcpInventoryUrl = mcpInventoryUrl; }

    public String getMcpMediaProcessingUrl() { return mcpMediaProcessingUrl; }
    public void setMcpMediaProcessingUrl(String mcpMediaProcessingUrl) {
        this.mcpMediaProcessingUrl = mcpMediaProcessingUrl;
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
