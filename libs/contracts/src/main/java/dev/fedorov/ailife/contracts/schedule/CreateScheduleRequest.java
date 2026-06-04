package dev.fedorov.ailife.contracts.schedule;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Either supply {@code cron} (recurring, next_run computed from cron) OR
 * {@code runAt} (one-shot). Validation rejects both/neither.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateScheduleRequest(
        UUID householdId,
        String ownerAgent,
        String kind,
        String cron,
        Instant runAt,
        JsonNode payload) {
}
