package dev.fedorov.ailife.orchestrator.conversation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where conversation-service lives. When enabled, the orchestrator checks for an active route-lock
 * before classifying — a reply to an agent's open question is routed straight back to that agent.
 * Disabled = the check is a no-op (every message classifies normally); routing never blocks on it.
 */
@ConfigurationProperties(prefix = "orchestrator.conversation")
public class ConversationProperties {

    private boolean enabled = true;
    private String url = "http://conversation-service:8089";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
}
