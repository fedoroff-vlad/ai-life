package dev.fedorov.ailife.agents.coordinator.config;

import dev.fedorov.ailife.agentruntime.config.SharedClientProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound HTTP destinations the coordinator agent talks to. It owns no domain-MCP and binds nothing
 * domain-specific — it only needs the three shared platform services every agent uses: profile
 * (household fan-out for a proactive surface), notifier (delivering that surface), and memory
 * (the second-brain recall that feeds the synthesis). The clients themselves come from
 * {@code agent-runtime}'s {@code AgentRuntimeConfig}, which reads these URLs via
 * {@link SharedClientProperties}.
 */
@ConfigurationProperties(prefix = "coordinator-agent")
public class CoordinatorAgentProperties implements SharedClientProperties {

    private String profileServiceUrl = "http://profile-service:8082";
    private String notifierUrl = "http://notifier-service:8084";
    private String memoryServiceUrl = "http://memory-service:8087";

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
