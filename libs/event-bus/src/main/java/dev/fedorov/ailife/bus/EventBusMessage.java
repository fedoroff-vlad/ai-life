package dev.fedorov.ailife.bus;

import java.time.Instant;
import java.util.UUID;

/**
 * One async event carried over the bus, mirroring a {@code bus.outbox} row.
 *
 * <p>{@code payload} is a JSON document as a raw string — the bus is payload-agnostic
 * (callers serialise their own contract DTO), so the envelope stays decoupled from
 * any single Jackson model. {@code householdId} may be null for system-wide events.
 */
public record EventBusMessage(
        UUID id,
        String topic,
        UUID householdId,
        String payload,
        Instant occurredAt) {
}
