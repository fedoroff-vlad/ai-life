package dev.fedorov.ailife.agents.coordinator.config;

import dev.fedorov.ailife.agentruntime.config.SharedClientProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Outbound HTTP destinations the coordinator agent talks to. It owns no domain-MCP and binds nothing
 * domain-specific — it only needs the three shared platform services every agent uses: profile
 * (household fan-out for a proactive surface), notifier (delivering that surface), and memory
 * (the second-brain recall that feeds the synthesis). The clients themselves come from
 * {@code agent-runtime}'s {@code AgentRuntimeConfig}, which reads these URLs via
 * {@link SharedClientProperties}.
 *
 * <p><b>Slice B2</b> adds the inter-agent {@code orchestratorUrl} (the hub the coordinator reaches
 * specialists through — agents never call each other directly) and the {@link Specialist} roster: the
 * agents the coordinator may consult live via the {@code brief} read-action. An empty roster (the
 * default) keeps the coordinator memory-only, so the specialist-gather leg is opt-in per deployment.
 */
@ConfigurationProperties(prefix = "coordinator-agent")
public class CoordinatorAgentProperties implements SharedClientProperties {

    private String profileServiceUrl = "http://profile-service:8082";
    private String notifierUrl = "http://notifier-service:8084";
    private String memoryServiceUrl = "http://memory-service:8087";
    private String orchestratorUrl = "http://orchestrator:8083";

    /** Specialists the coordinator may consult live via their {@code brief} read-action. */
    private List<Specialist> specialists = new ArrayList<>();

    /**
     * Max synthesis rounds in the bounded confidence loop (Slice E-later). {@code 1} = one-shot (today's
     * behaviour, the self-check is never called); {@code 2} (default) allows at most one re-gather when the
     * self-check judges the first answer under-confident. Values below 1 are clamped to 1.
     */
    private int maxRounds = 2;

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

    public String getOrchestratorUrl() { return orchestratorUrl; }
    public void setOrchestratorUrl(String orchestratorUrl) {
        this.orchestratorUrl = orchestratorUrl;
    }

    public List<Specialist> getSpecialists() { return specialists; }
    public void setSpecialists(List<Specialist> specialists) {
        this.specialists = specialists == null ? new ArrayList<>() : specialists;
    }

    public int getMaxRounds() { return maxRounds; }
    public void setMaxRounds(int maxRounds) { this.maxRounds = Math.max(1, maxRounds); }

    /**
     * One consultable specialist: its agent {@code name} (the hub {@code targetAgent}) and a one-line
     * {@code expertise} the FAST planning step reads to decide whether the specialist bears on a request.
     */
    public static class Specialist {
        private String name;
        private String expertise = "";

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getExpertise() { return expertise; }
        public void setExpertise(String expertise) { this.expertise = expertise; }
    }
}
