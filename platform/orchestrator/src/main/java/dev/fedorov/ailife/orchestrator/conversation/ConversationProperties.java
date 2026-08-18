package dev.fedorov.ailife.orchestrator.conversation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where conversation-service lives. When enabled, the orchestrator checks for an active route-lock
 * before classifying — a reply to an agent's open question is routed straight back to that agent.
 * Disabled = the check is a no-op (every message classifies normally); routing never blocks on it.
 *
 * <p>{@code correctionWindowSeconds} is the TTL the orchestrator gives a recorded {@code last_route}
 * (misroute-repair #484): a fresh routing is remembered only this long, so a correction re-routes
 * "right after a reply" but a stale prior route ages out and the next message classifies normally.
 */
@ConfigurationProperties(prefix = "orchestrator.conversation")
public class ConversationProperties {

    private boolean enabled = true;
    private String url = "http://conversation-service:8089";
    private long correctionWindowSeconds = 180;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public long getCorrectionWindowSeconds() { return correctionWindowSeconds; }
    public void setCorrectionWindowSeconds(long correctionWindowSeconds) {
        this.correctionWindowSeconds = correctionWindowSeconds;
    }
}
