package dev.fedorov.ailife.contracts.schedule;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted on the event bus (topic {@code schedule.fired}) each time scheduler-service
 * wakes an agent for a due schedule. Records that the wake happened — consumers can
 * audit or react asynchronously without sitting on the synchronous wake path.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScheduleFiredEvent(
        UUID scheduleId,
        UUID householdId,
        String ownerAgent,
        String kind,
        Instant firedAt) {
}
