package dev.fedorov.ailife.agents.finance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound HTTP destinations the finance-agent talks to. Both URLs are required
 * for the trigger fan-out flow (profile lookup → notifier send).
 *
 * <p>Shape mirrors calendar-agent's {@code CalendarAgentProperties}; lift to a
 * shared {@code libs/agent-runtime} once a third agent ships the same fan-out.
 */
@ConfigurationProperties(prefix = "finance-agent")
public class FinanceAgentProperties {

    private String profileServiceUrl = "http://profile-service:8082";
    private String notifierUrl = "http://notifier-service:8084";
    private String memoryServiceUrl = "http://memory-service:8087";
    private String mcpFinanceUrl = "http://mcp-finance:8092";

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

    public String getMcpFinanceUrl() { return mcpFinanceUrl; }
    public void setMcpFinanceUrl(String mcpFinanceUrl) {
        this.mcpFinanceUrl = mcpFinanceUrl;
    }
}
