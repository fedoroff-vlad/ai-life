package dev.fedorov.ailife.mcp.briefing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * mcp-briefing configuration that isn't driven by Spring Boot's {@code spring.*}
 * keys. Today the only knobs are around the auto-registered {@code
 * briefing.digest} wake (BR-f2): the scheduler-service base URL and the dispatch
 * metadata (owner agent + trigger kind). They sit under {@code mcp-briefing.*}
 * so future config can live alongside without ballooning the prefix. Mirrors
 * {@code McpFinanceProperties}.
 */
@ConfigurationProperties(prefix = "mcp-briefing")
public class McpBriefingProperties {

    private String schedulerUrl = "http://scheduler-service:8085";
    private final Schedule schedule = new Schedule();

    public String getSchedulerUrl() { return schedulerUrl; }
    public void setSchedulerUrl(String schedulerUrl) { this.schedulerUrl = schedulerUrl; }
    public Schedule getSchedule() { return schedule; }

    /**
     * The auto-registered per-profile morning wake. Unlike finance's budget
     * alert the cron is per-row (built from the profile's schedule_time +
     * timezone), so only the dispatch metadata lives here — the briefing-agent
     * that owns the trigger and the wake kind it declares in its manifest.
     */
    public static class Schedule {
        private String ownerAgent = "briefing";
        private String triggerKind = "briefing.digest";

        public String getOwnerAgent() { return ownerAgent; }
        public void setOwnerAgent(String ownerAgent) { this.ownerAgent = ownerAgent; }
        public String getTriggerKind() { return triggerKind; }
        public void setTriggerKind(String triggerKind) { this.triggerKind = triggerKind; }
    }
}
