package dev.fedorov.ailife.scheduler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "scheduler")
public class SchedulerProperties {

    private String orchestratorBaseUrl = "http://orchestrator:8083";
    /** Tick fixed-delay; lower bound enforced by Spring (millis). */
    private long tickMillis = 30_000L;
    /** Max rows to process per tick to keep one wakeup batch bounded. */
    private int batchSize = 50;

    public String getOrchestratorBaseUrl() { return orchestratorBaseUrl; }
    public void setOrchestratorBaseUrl(String orchestratorBaseUrl) {
        this.orchestratorBaseUrl = orchestratorBaseUrl;
    }
    public long getTickMillis() { return tickMillis; }
    public void setTickMillis(long tickMillis) { this.tickMillis = tickMillis; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
}
