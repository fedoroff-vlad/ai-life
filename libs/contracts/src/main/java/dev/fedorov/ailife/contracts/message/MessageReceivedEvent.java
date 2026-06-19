package dev.fedorov.ailife.contracts.message;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Published on the event bus (topic {@code message.received}) when an inbound user
 * message is processed — the async signal for passive learning (Stage 4 /
 * memory-from-chat). memory-service consumes it and extracts durable facts off the
 * user's request path; other consumers may react too.
 *
 * <p>Minimal by design: {@code householdId} + {@code userId} scope, the {@code text}
 * to learn from, and free-text {@code source} provenance (e.g. {@code "telegram"}).
 * Richer surfaces (attachments, a parsed receipt) get their own event later.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageReceivedEvent(UUID householdId, UUID userId, String text, String source) {

    /** Bus topic this event is published under. */
    public static final String TOPIC = "message.received";
}
