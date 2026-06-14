package dev.fedorov.ailife.orchestrator.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Known remote agents the orchestrator will scrape on startup. Echo agent is
 * always available locally as the fallback and does NOT appear in this list.
 *
 * <p>Example yaml:
 * <pre>
 * orchestrator:
 *   agents:
 *     - name: calendar
 *       base-url: http://calendar-agent:8086
 * </pre>
 */
@ConfigurationProperties(prefix = "orchestrator")
public class AgentRegistryProperties {

    private List<Registration> agents = new ArrayList<>();

    /**
     * Agent that captures any actionable message matching no specialized domain — the
     * GTD "anything not calendar/finance → inbox" catch-all. Empty (default behaviour
     * when unset) keeps {@code echo} as the only fallback. Self-disables to {@code echo}
     * if the named agent isn't actually registered, so it's safe to leave set even when
     * that agent isn't deployed.
     */
    private String catchAllAgent = "";

    public List<Registration> getAgents() { return agents; }
    public void setAgents(List<Registration> agents) { this.agents = agents; }
    public String getCatchAllAgent() { return catchAllAgent; }
    public void setCatchAllAgent(String catchAllAgent) { this.catchAllAgent = catchAllAgent; }

    public static class Registration {
        private String name;
        private String baseUrl;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }
}
