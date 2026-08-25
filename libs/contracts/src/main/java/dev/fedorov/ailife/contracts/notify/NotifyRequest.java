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
 * reply leaves it {@code false} and is never gated. The two-arg constructor keeps every existing
 * caller compiling ({@code proactive=false}); a missing field also deserialises to {@code false}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotifyRequest(UUID userId, String text, boolean proactive) {

    /** Back-compat / reactive default: a plain send that is never proactively gated. */
    public NotifyRequest(UUID userId, String text) {
        this(userId, text, false);
    }
}
