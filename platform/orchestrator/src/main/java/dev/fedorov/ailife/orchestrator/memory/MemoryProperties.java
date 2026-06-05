package dev.fedorov.ailife.orchestrator.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where memory-service lives and how aggressively the orchestrator pulls from it
 * before classification. Disabled = recall is a no-op; the orchestrator still
 * works, just without long-term context in the intent prompt.
 */
@ConfigurationProperties(prefix = "orchestrator.memory")
public class MemoryProperties {

    private boolean enabled = true;
    private String url = "http://memory-service:8087";
    /** Top-k requested from memory-service. Server-side cap still applies. */
    private int recallK = 3;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public int getRecallK() { return recallK; }
    public void setRecallK(int recallK) { this.recallK = recallK; }
}
