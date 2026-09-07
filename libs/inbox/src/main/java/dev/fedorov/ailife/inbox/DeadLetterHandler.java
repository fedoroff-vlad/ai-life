package dev.fedorov.ailife.inbox;

/**
 * Called once when a message goes terminal {@code DEAD} (a poison message that failed
 * {@code max-attempts} redrives), so the ingress can tell the user their message could not be
 * processed instead of it vanishing. Best-effort: a throw here is swallowed — the row is already
 * committed {@code DEAD} and must not loop.
 */
@FunctionalInterface
public interface DeadLetterHandler {

    void onDead(InboxMessage message);

    /** A no-op sink for callers that don't need dead-letter notification. */
    static DeadLetterHandler noop() {
        return message -> { };
    }
}
