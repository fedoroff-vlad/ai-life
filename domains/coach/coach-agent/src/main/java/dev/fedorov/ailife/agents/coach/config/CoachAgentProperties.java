package dev.fedorov.ailife.agents.coach.config;

import dev.fedorov.ailife.agentruntime.config.SharedClientProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound HTTP destinations the coach agent talks to: the shared platform services (profile /
 * notifier / memory, backing the {@code agent-runtime} clients) plus its own domain store,
 * {@code mcp-coach}, reached via the {@code /internal/coach/*} REST passthroughs (no MCP transport —
 * deterministic agent→store calls per the tool-call transport split).
 */
@ConfigurationProperties(prefix = "coach-agent")
public class CoachAgentProperties implements SharedClientProperties {

    private String profileServiceUrl = "http://profile-service:8082";
    private String notifierUrl = "http://notifier-service:8084";
    private String memoryServiceUrl = "http://memory-service:8087";
    private String mcpCoachUrl = "http://mcp-coach:8121";
    /** How many recent household notes to pull before filtering down to the subject's own material. */
    private int notesScanLimit = 100;
    /** Cap on the subject notes handed to the Reflect synthesis (newest first). */
    private int gatheredNotesMax = 15;

    public String getMcpCoachUrl() { return mcpCoachUrl; }
    public void setMcpCoachUrl(String mcpCoachUrl) { this.mcpCoachUrl = mcpCoachUrl; }

    public int getNotesScanLimit() { return notesScanLimit; }
    public void setNotesScanLimit(int notesScanLimit) { this.notesScanLimit = notesScanLimit; }

    public int getGatheredNotesMax() { return gatheredNotesMax; }
    public void setGatheredNotesMax(int gatheredNotesMax) { this.gatheredNotesMax = gatheredNotesMax; }

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
