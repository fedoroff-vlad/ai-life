package dev.fedorov.ailife.bus;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for the event bus. {@code enabled} gates the listener (consumer) side only —
 * the {@link OutboxPublisher} producer is always available so a service can emit events
 * even where nothing consumes them yet.
 */
@ConfigurationProperties(prefix = "event-bus")
public class EventBusProperties {

    /** Start the listener container on context start. Producer is unaffected. */
    private boolean enabled = true;

    /** The Postgres NOTIFY channel both sides use. */
    private String channel = EventBus.DEFAULT_CHANNEL;

    /** How long the listener blocks for a NOTIFY before the safety drain poll. */
    private long pollMillis = 5000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public long getPollMillis() {
        return pollMillis;
    }

    public void setPollMillis(long pollMillis) {
        this.pollMillis = pollMillis;
    }
}
