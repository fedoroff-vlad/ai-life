package dev.fedorov.ailife.agents.researcher.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound HTTP destinations the researcher talks to. {@code mcpWebUrl} is the shared web
 * capability (the deterministic research flow calls its {@code /internal/*} passthroughs). The
 * profile / notifier / memory URLs back the shared {@code agent-runtime} clients every agent
 * imports — the researcher doesn't fan out or recall in the MVP, but the runtime beans need them.
 */
@ConfigurationProperties(prefix = "researcher-agent")
public class ResearcherAgentProperties {

    private String mcpWebUrl = "http://mcp-web:8098";
    private String profileServiceUrl = "http://profile-service:8082";
    private String notifierUrl = "http://notifier-service:8084";
    private String memoryServiceUrl = "http://memory-service:8087";

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
