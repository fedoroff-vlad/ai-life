package dev.fedorov.ailife.mcp.tasks.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * mcp-tasks configuration that isn't driven by Spring Boot's {@code spring.*}
 * keys. Today the only knobs are around the auto-registered {@code weekly.review}
 * schedule (PR61): scheduler URL + the recurring cron, owner agent and trigger
 * kind. They sit under {@code mcp-tasks.review.*} so future config can live
 * alongside without ballooning the top-level prefix (mirrors mcp-finance).
 */
@ConfigurationProperties(prefix = "mcp-tasks")
public class McpTasksProperties {

    private String schedulerUrl = "http://scheduler-service:8085";
    private final Review review = new Review();

    public String getSchedulerUrl() { return schedulerUrl; }
    public void setSchedulerUrl(String schedulerUrl) { this.schedulerUrl = schedulerUrl; }
    public Review getReview() { return review; }

    /**
     * Auto-registered {@code weekly.review} schedule — one recurring cron per
     * household (the GTD weekly review). Default = Monday 09:00 UTC; the agent's
     * {@code weekly-review} skill emits SKIP on a clean week, so a fixed weekly
     * cadence is fine regardless of how much there is to review.
     */
    public static class Review {
        private String cron = "0 0 9 * * MON";
        private String ownerAgent = "tasks";
        private String triggerKind = "weekly.review";

        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }
        public String getOwnerAgent() { return ownerAgent; }
        public void setOwnerAgent(String ownerAgent) { this.ownerAgent = ownerAgent; }
        public String getTriggerKind() { return triggerKind; }
        public void setTriggerKind(String triggerKind) { this.triggerKind = triggerKind; }
    }
}
