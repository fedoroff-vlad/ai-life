package dev.fedorov.ailife.agents.tasks.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound HTTP destinations the tasks-agent talks to. These three (profile / notifier / memory)
 * back the qualified {@code WebClient} beans that {@code libs/agent-runtime}'s
 * {@code AgentRuntimeConfig} wires its {@code ProfileClient} / {@code NotifierClient} /
 * {@code MemoryClient} on — so they must exist even in the skeleton. The mcp-tasks URL + tool
 * wiring land with the first skill.
 */
@ConfigurationProperties(prefix = "tasks-agent")
public class TasksAgentProperties {

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
