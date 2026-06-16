package dev.fedorov.ailife.contracts.notify;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Published on the event bus (topic {@code notify.requested}) to ask notifier-service to
 * deliver a message to one user asynchronously — the decoupled alternative to a direct
 * {@code POST /v1/notify} HTTP call. Notifier resolves the user's {@code telegram_user_id}
 * and forwards through gateway-telegram exactly as the synchronous path does.
 *
 * <p>Single recipient by design: notifier stays domain-agnostic (it only sends text).
 * A producer that wants to reach a whole household enumerates its members and emits one
 * event per user — knowing who is in a household is a domain concern, not notifier's.
 * {@code source} is free-text provenance for audit (e.g. {@code "calendar.birthday"}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotifyRequestedEvent(UUID userId, String text, String source) {

    /** Bus topic this event is published under. */
    public static final String TOPIC = "notify.requested";
}
