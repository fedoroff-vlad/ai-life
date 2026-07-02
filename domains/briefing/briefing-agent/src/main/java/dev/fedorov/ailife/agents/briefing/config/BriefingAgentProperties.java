package dev.fedorov.ailife.agents.briefing.config;

import dev.fedorov.ailife.agentruntime.config.SharedClientProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound HTTP destinations the briefing agent talks to. {@code mcpBriefingUrl} is its own data (the
 * briefing domain-MCP); {@code mcpWeatherUrl} is the shared weather+geocoding capability the profiler
 * (BR-c) and the digest gather (BR-d) call over its {@code /internal/*} passthroughs; {@code mcpWebUrl}
 * is the shared web capability for news; {@code mcpCaldavUrl} / {@code mcpFinanceUrl} are the calendar
 * and finance domain-MCPs the digest gathers today's agenda + a spend snapshot from over their
 * deterministic {@code /internal/*} read passthroughs (BR-d — reads only, no MCP-SSE binding). The
 * profile / notifier / memory URLs back the shared {@code agent-runtime} clients every agent imports.
 * {@code mediaServiceUrl} stores the rendered HTML digest board and {@code publicMediaBaseUrl} is the
 * base the returned open-link is built from (BR-e).
 */
@ConfigurationProperties(prefix = "briefing-agent")
public class BriefingAgentProperties implements SharedClientProperties {

    private String mcpBriefingUrl = "http://mcp-briefing:8114";
    private String mcpWeatherUrl = "http://mcp-weather:8113";
    private String mcpWebUrl = "http://mcp-web:8098";
    private String mcpCaldavUrl = "http://mcp-caldav:8090";
    private String mcpFinanceUrl = "http://mcp-finance:8092";
    private String mediaServiceUrl = "http://media-service:8088";
    private String profileServiceUrl = "http://profile-service:8082";
    private String notifierUrl = "http://notifier-service:8084";
    private String memoryServiceUrl = "http://memory-service:8087";

    /**
     * Public base URL a stored deliverable link is built from (so the user can open the digest board on
     * any device). Defaults to the internal media-service URL; set to a publicly-reachable gateway base
     * in a real deployment. The link is {@code <base>/v1/media/{id}}.
     */
    private String publicMediaBaseUrl = "http://media-service:8088";

    public String getMcpBriefingUrl() { return mcpBriefingUrl; }
    public void setMcpBriefingUrl(String mcpBriefingUrl) { this.mcpBriefingUrl = mcpBriefingUrl; }

    public String getMcpWeatherUrl() { return mcpWeatherUrl; }
    public void setMcpWeatherUrl(String mcpWeatherUrl) { this.mcpWeatherUrl = mcpWeatherUrl; }

    public String getMcpWebUrl() { return mcpWebUrl; }
    public void setMcpWebUrl(String mcpWebUrl) { this.mcpWebUrl = mcpWebUrl; }

    public String getMcpCaldavUrl() { return mcpCaldavUrl; }
    public void setMcpCaldavUrl(String mcpCaldavUrl) { this.mcpCaldavUrl = mcpCaldavUrl; }

    public String getMcpFinanceUrl() { return mcpFinanceUrl; }
    public void setMcpFinanceUrl(String mcpFinanceUrl) { this.mcpFinanceUrl = mcpFinanceUrl; }

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
