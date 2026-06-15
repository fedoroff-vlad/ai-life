package dev.fedorov.ailife.bus;

/** Shared constants for the Postgres LISTEN/NOTIFY event bus. */
public final class EventBus {

    /** The single Postgres NOTIFY channel all ai-life events flow over. */
    public static final String DEFAULT_CHANNEL = "ailife_events";

    private EventBus() {
    }
}
