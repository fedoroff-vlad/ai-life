package dev.fedorov.ailife.agents.travel.config;

import dev.fedorov.ailife.agentruntime.config.SharedClientProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound HTTP destinations the travel agent talks to. {@code mcpTravelUrl} is its own data (the
 * travel domain-MCP); {@code mcpWeatherUrl} is the shared weather+geocoding capability the profiler
 * (TR-c) calls over its {@code /internal/*} passthroughs (geocode now; the {@code climate} season
 * substrate in TR-d); {@code mcpWebUrl} is the shared web capability bound for the TR-d destination
 * research gather. {@code orchestratorUrl} is the hub the TR-d planner reaches to invoke the finance and
 * calendar agents' read-only {@code brief} action (agents never call each other directly). The profile /
 * notifier / memory URLs back the shared {@code agent-runtime} clients every agent imports.
 * {@code mediaServiceUrl} / {@code publicMediaBaseUrl} back the TR-e HTML travel board (render → store →
 * link, the shared {@code DeliverablePublisher} seam); {@code mcpChartRenderUrl} is the shared
 * chart-render capability the board's climate-by-month curve is rendered by.
 */
@ConfigurationProperties(prefix = "travel-agent")
public class TravelAgentProperties implements SharedClientProperties {

    private String mcpTravelUrl = "http://mcp-travel:8123";
    private String mcpWeatherUrl = "http://mcp-weather:8113";
    private String mcpWebUrl = "http://mcp-web:8098";
    private String orchestratorUrl = "http://orchestrator:8083";
    private String profileServiceUrl = "http://profile-service:8082";
    private String notifierUrl = "http://notifier-service:8084";
    private String memoryServiceUrl = "http://memory-service:8087";
    private String mediaServiceUrl = "http://media-service:8088";
    private String publicMediaBaseUrl = "http://media-service:8088";
    private String mcpChartRenderUrl = "http://mcp-chart-render:8120";

    public String getMcpTravelUrl() { return mcpTravelUrl; }
    public void setMcpTravelUrl(String mcpTravelUrl) { this.mcpTravelUrl = mcpTravelUrl; }

    public String getMcpWeatherUrl() { return mcpWeatherUrl; }
    public void setMcpWeatherUrl(String mcpWeatherUrl) { this.mcpWeatherUrl = mcpWeatherUrl; }

    public String getMcpWebUrl() { return mcpWebUrl; }
    public void setMcpWebUrl(String mcpWebUrl) { this.mcpWebUrl = mcpWebUrl; }

    public String getOrchestratorUrl() { return orchestratorUrl; }
    public void setOrchestratorUrl(String orchestratorUrl) { this.orchestratorUrl = orchestratorUrl; }

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

    public String getMediaServiceUrl() { return mediaServiceUrl; }
    public void setMediaServiceUrl(String mediaServiceUrl) { this.mediaServiceUrl = mediaServiceUrl; }

    public String getPublicMediaBaseUrl() { return publicMediaBaseUrl; }
    public void setPublicMediaBaseUrl(String publicMediaBaseUrl) {
        this.publicMediaBaseUrl = publicMediaBaseUrl;
    }

    public String getMcpChartRenderUrl() { return mcpChartRenderUrl; }
    public void setMcpChartRenderUrl(String mcpChartRenderUrl) {
        this.mcpChartRenderUrl = mcpChartRenderUrl;
    }
}
