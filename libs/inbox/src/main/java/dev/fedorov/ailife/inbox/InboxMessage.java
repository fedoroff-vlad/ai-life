package dev.fedorov.ailife.inbox;

import java.time.Instant;
import java.util.UUID;

/**
 * One durable inbound message, mirroring a {@code bus.inbox} row handed to a redrive handler.
 *
 * <p>{@code dedupKey} is the ingress-idempotency key (for Telegram: the {@code update_id}) that the
 * {@code UNIQUE} constraint dedups on, so a re-delivered or re-recorded update never double-persists.
 * {@code payload} is a JSON document as a raw string — the inbox is payload-agnostic (the caller
 * serialises its own envelope), so this stays decoupled from any single Jackson model.
 * {@code attempts} is the number of redrive attempts already made against this row.
 */
public record InboxMessage(
        UUID id,
        String dedupKey,
        String payload,
        int attempts,
        Instant createdAt) {
}
