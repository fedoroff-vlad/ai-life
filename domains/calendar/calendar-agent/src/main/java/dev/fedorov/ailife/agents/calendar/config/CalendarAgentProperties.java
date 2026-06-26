package dev.fedorov.ailife.agents.calendar.config;

import dev.fedorov.ailife.agentruntime.config.SharedClientProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound HTTP destinations the calendar-agent talks to. Both URLs are
 * required for the trigger fan-out flow (profile lookup → notifier send).
 */
@ConfigurationProperties(prefix = "calendar-agent")
public class CalendarAgentProperties implements SharedClientProperties {

    private String profileServiceUrl = "http://profile-service:8082";
    private String notifierUrl = "http://notifier-service:8084";
    private String icsImportUrl = "http://mcp-ics-import:8091";
    private String memoryServiceUrl = "http://memory-service:8087";
    private String mcpCaldavUrl = "http://mcp-caldav:8090";
    private String orchestratorUrl = "http://orchestrator:8083";

    public String getProfileServiceUrl() { return profileServiceUrl; }
    public void setProfileServiceUrl(String profileServiceUrl) {
        this.profileServiceUrl = profileServiceUrl;
    }

    public String getNotifierUrl() { return notifierUrl; }
    public void setNotifierUrl(String notifierUrl) { this.notifierUrl = notifierUrl; }

    public String getIcsImportUrl() { return icsImportUrl; }
    public void setIcsImportUrl(String icsImportUrl) { this.icsImportUrl = icsImportUrl; }
    public String getMemoryServiceUrl() { return memoryServiceUrl; }
    public void setMemoryServiceUrl(String memoryServiceUrl) { this.memoryServiceUrl = memoryServiceUrl; }

    public String getMcpCaldavUrl() { return mcpCaldavUrl; }
    public void setMcpCaldavUrl(String mcpCaldavUrl) { this.mcpCaldavUrl = mcpCaldavUrl; }

    public String getOrchestratorUrl() { return orchestratorUrl; }
    public void setOrchestratorUrl(String orchestratorUrl) { this.orchestratorUrl = orchestratorUrl; }
}
