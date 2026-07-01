package dev.fedorov.ailife.agents.briefing.config;

import dev.fedorov.ailife.agentruntime.config.SharedClientProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound HTTP destinations the briefing agent talks to. {@code mcpBriefingUrl} is its own data (the
 * briefing domain-MCP); {@code mcpWeatherUrl} is the shared weather+geocoding capability the profiler
 * (BR-c) and the digest gather (BR-d) call over its {@code /internal/*} passthroughs; {@code mcpWebUrl}
 * is the shared web capability for news (BR-d). The profile / notifier / memory URLs back the shared
 * {@code agent-runtime} clients every agent imports.
 */
@ConfigurationProperties(prefix = "briefing-agent")
public class BriefingAgentProperties implements SharedClientProperties {

    private String mcpBriefingUrl = "http://mcp-briefing:8114";
    private String mcpWeatherUrl = "http://mcp-weather:8113";
    private String mcpWebUrl = "http://mcp-web:8098";
    private String profileServiceUrl = "http://profile-service:8082";
    private String notifierUrl = "http://notifier-service:8084";
    private String memoryServiceUrl = "http://memory-service:8087";

    public String getMcpBriefingUrl() { return mcpBriefingUrl; }
    public void setMcpBriefingUrl(String mcpBriefingUrl) { this.mcpBriefingUrl = mcpBriefingUrl; }

    public String getMcpWeatherUrl() { return mcpWeatherUrl; }
    public void setMcpWeatherUrl(String mcpWeatherUrl) { this.mcpWeatherUrl = mcpWeatherUrl; }

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
