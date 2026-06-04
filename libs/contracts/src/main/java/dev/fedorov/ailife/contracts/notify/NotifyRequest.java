package dev.fedorov.ailife.contracts.notify;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * What downstream services (scheduler, agents) send to notifier-service.
 * Notifier looks up the user's telegram_user_id and forwards to
 * gateway-telegram's internal send endpoint.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotifyRequest(UUID userId, String text) {
}
