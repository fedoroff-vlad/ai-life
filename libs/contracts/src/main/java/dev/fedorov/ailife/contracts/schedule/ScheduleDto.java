package dev.fedorov.ailife.contracts.schedule;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * One scheduled wake-up. Either {@code cron} OR a one-shot run (only
 * {@code nextRunTs} set, no cron). The agent identified by {@code ownerAgent}
 * is woken by orchestrator with {@code payload} when due.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScheduleDto(
        UUID id,
        UUID householdId,
        String ownerAgent,
        String kind,
        String cron,
        JsonNode payload,
        boolean enabled,
        Instant nextRunTs,
        Instant lastRunTs,
        Instant createdAt) {
}
