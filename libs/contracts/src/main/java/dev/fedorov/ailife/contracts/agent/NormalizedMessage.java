package dev.fedorov.ailife.contracts.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Output of {@code gateway-telegram} after media has been processed. The orchestrator
 * and every agent downstream see this — they never touch raw Telegram updates.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NormalizedMessage(
        UUID userId,
        UUID householdId,
        MessageScope scope,
        String text,
        List<Attachment> attachments,
        String sourceChannel,
        String sourceMessageId,
        Instant receivedAt) {

    public NormalizedMessage {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
        if (scope == null) {
            scope = MessageScope.PRIVATE;
        }
    }
}
