package dev.fedorov.ailife.agents.travel.config;

import dev.fedorov.ailife.agentruntime.config.SharedClientProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound HTTP destinations the travel agent talks to. {@code mcpTravelUrl} is its own data (the
 * travel domain-MCP); {@code mcpWeatherUrl} is the shared weather+geocoding capability the profiler
 * (TR-c) calls over its {@code /internal/*} passthroughs (geocode now; the {@code climate} season
 * substrate in TR-d); {@code mcpWebUrl} is the shared web capability bound for the TR-d destination
 * research gather. The profile / notifier / memory URLs back the shared {@code agent-runtime} clients
 * every agent imports.
 */
@ConfigurationProperties(prefix = "travel-agent")
public class TravelAgentProperties implements SharedClientProperties {

    private String mcpTravelUrl = "http://mcp-travel:8123";
    private String mcpWeatherUrl = "http://mcp-weather:8113";
    private String mcpWebUrl = "http://mcp-web:8098";
    private String profileServiceUrl = "http://profile-service:8082";
    private String notifierUrl = "http://notifier-service:8084";
    private String memoryServiceUrl = "http://memory-service:8087";

    public String getMcpTravelUrl() { return mcpTravelUrl; }
    public void setMcpTravelUrl(String mcpTravelUrl) { this.mcpTravelUrl = mcpTravelUrl; }

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
