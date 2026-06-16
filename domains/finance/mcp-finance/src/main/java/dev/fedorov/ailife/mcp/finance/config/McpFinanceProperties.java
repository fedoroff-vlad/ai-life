package dev.fedorov.ailife.mcp.finance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * mcp-finance configuration that isn't driven by Spring Boot's
 * {@code spring.*} keys. Today the only knobs are around the auto-registered
 * {@code budget.alert} schedule (PR27b): cron expression, owner agent, and
 * trigger kind. They sit under {@code mcp-finance.budget.*} so future
 * config (e.g. matview refresh) can live alongside without ballooning the
 * top-level prefix.
 */
@ConfigurationProperties(prefix = "mcp-finance")
public class McpFinanceProperties {

    private String schedulerUrl = "http://scheduler-service:8085";
    private final Budget budget = new Budget();
    private final Recurring recurring = new Recurring();
    private final Transaction transaction = new Transaction();

    public String getSchedulerUrl() { return schedulerUrl; }
    public void setSchedulerUrl(String schedulerUrl) { this.schedulerUrl = schedulerUrl; }
    public Budget getBudget() { return budget; }
    public Recurring getRecurring() { return recurring; }
    public Transaction getTransaction() { return transaction; }

    public static class Budget {
        /**
         * Cron expression for the auto-registered budget.alert schedule. Spring
         * {@code CronExpression}, UTC. Default = 9:00 UTC every day so the
         * scheduler wakes the agent once a day regardless of period length;
         * finance-agent then asks mcp-finance for the live status in the
         * current period window and either alerts or SKIPs.
         */
        private String cron = "0 0 9 * * *";
        private String ownerAgent = "finance";
        private String triggerKind = "budget.alert";

        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }
        public String getOwnerAgent() { return ownerAgent; }
        public void setOwnerAgent(String ownerAgent) { this.ownerAgent = ownerAgent; }
        public String getTriggerKind() { return triggerKind; }
        public void setTriggerKind(String triggerKind) { this.triggerKind = triggerKind; }
    }

    /**
     * Auto-registered {@code recurring.due} schedule. Unlike budget alerts the
     * cron is per-row (taken from {@code fin_recurring.cron}), so only the
     * dispatch metadata lives here.
     */
    public static class Recurring {
        private String ownerAgent = "finance";
        private String triggerKind = "recurring.due";

        public String getOwnerAgent() { return ownerAgent; }
        public void setOwnerAgent(String ownerAgent) { this.ownerAgent = ownerAgent; }
        public String getTriggerKind() { return triggerKind; }
        public void setTriggerKind(String triggerKind) { this.triggerKind = triggerKind; }
    }

    /**
     * One-shot {@code transaction.uncategorised} wake fired by
     * {@code add_transaction} when a row lands without a category. Per-row
     * runAt = "now" — scheduler-service picks it up on the next tick (default
     * 30s).
     */
    public static class Transaction {
        private String ownerAgent = "finance";
        private String triggerKind = "transaction.uncategorised";

        public String getOwnerAgent() { return ownerAgent; }
        public void setOwnerAgent(String ownerAgent) { this.ownerAgent = ownerAgent; }
        public String getTriggerKind() { return triggerKind; }
        public void setTriggerKind(String triggerKind) { this.triggerKind = triggerKind; }
    }
}
