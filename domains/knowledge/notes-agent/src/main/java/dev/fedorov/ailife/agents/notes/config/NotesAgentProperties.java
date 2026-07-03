package dev.fedorov.ailife.agents.notes.config;

import dev.fedorov.ailife.agentruntime.config.SharedClientProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound HTTP destinations the notes agent talks to. It owns no MCP — the knowledge substrate is
 * memory-service itself, reached through the shared {@code agent-runtime} clients (recall) plus a thin
 * {@code NoteClient} over the same {@code memoryServiceWebClient} for the {@code /v1/notes} surface.
 * The profile / notifier URLs back the shared runtime clients every agent imports.
 */
@ConfigurationProperties(prefix = "notes-agent")
public class NotesAgentProperties implements SharedClientProperties {

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
