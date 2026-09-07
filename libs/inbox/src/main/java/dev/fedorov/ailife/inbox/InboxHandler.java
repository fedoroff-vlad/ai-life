package dev.fedorov.ailife.inbox;

/**
 * Redrive handler: re-attempts one {@link InboxMessage} (re-dispatch downstream + deliver the reply).
 * A handler that <b>throws</b> signals a failed attempt — the redriver reschedules the row with backoff
 * (or marks it {@code DEAD} once {@code max-attempts} is reached). A clean return marks it {@code PROCESSED}.
 *
 * <p>Redrive safety: the handler must be safe to run again for a message whose earlier attempt failed
 * <em>before any external side effect</em> — for the Telegram ingress that holds because the redrive
 * re-enters at the propose phase and the confirm-gate re-gates any real act (ADR-0004).
 */
@FunctionalInterface
public interface InboxHandler {

    void handle(InboxMessage message) throws Exception;
}
