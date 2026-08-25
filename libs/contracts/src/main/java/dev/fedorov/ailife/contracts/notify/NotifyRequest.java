package dev.fedorov.ailife.contracts.notify;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * What downstream services (scheduler, agents) send to notifier-service.
 * Notifier looks up the user's telegram_user_id and forwards to
 * gateway-telegram's internal send endpoint.
 *
 * <p>{@code proactive} marks a push that notifier may gate under the owner's proactive-UX
 * preferences (quiet hours / caps, #487) — a message the user did not just ask for. A reactive
 * reply leaves it {@code false} and is never gated. {@code source} is the free-text provenance /
 * <b>stream</b> id (e.g. {@code "briefing"}, {@code "resurfacing"}) — the key a user opts out of
 * per-stream (#487 PX-3); {@code null} means an unattributed send that no stream opt-out can match.
 * The shorter constructors keep every existing caller compiling; missing fields deserialise to
 * their defaults ({@code proactive=false}, {@code source=null}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotifyRequest(UUID userId, String text, boolean proactive, String source) {

    /** Back-compat / reactive default: a plain send that is never proactively gated. */
    public NotifyRequest(UUID userId, String text) {
        this(userId, text, false, null);
    }

    /** Proactive send with no stream attribution (not opt-out-able). */
    public NotifyRequest(UUID userId, String text, boolean proactive) {
        this(userId, text, proactive, null);
    }
}
