package dev.fedorov.ailife.contracts.notify;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Payload accepted by gateway-telegram's {@code POST /internal/send} endpoint
 * (Bearer {@code INTERNAL_API_TOKEN}). Only the gateway holds the bot token —
 * notifier and any future caller goes through here.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InternalSendRequest(long telegramUserId, String text) {
}
