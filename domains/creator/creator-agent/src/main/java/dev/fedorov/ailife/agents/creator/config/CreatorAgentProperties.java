package dev.fedorov.ailife.agents.creator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound HTTP destinations the creator talks to. {@code mcpCreatorUrl} is its own data (the creator
 * domain-MCP); {@code mcpWebUrl} is the shared web capability the deterministic trend gather (CR-d)
 * calls over its {@code /internal/*} passthroughs. The profile / notifier / memory URLs back the
 * shared {@code agent-runtime} clients every agent imports — the scaffold doesn't fan out or recall
 * yet, but the runtime beans need them.
 */
@ConfigurationProperties(prefix = "creator-agent")
public class CreatorAgentProperties {

    private String mcpCreatorUrl = "http://mcp-creator:8108";
    private String mcpWebUrl = "http://mcp-web:8098";
    private String profileServiceUrl = "http://profile-service:8082";
    private String notifierUrl = "http://notifier-service:8084";
    private String memoryServiceUrl = "http://memory-service:8087";

    public String getMcpCreatorUrl() { return mcpCreatorUrl; }
    public void setMcpCreatorUrl(String mcpCreatorUrl) { this.mcpCreatorUrl = mcpCreatorUrl; }

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
