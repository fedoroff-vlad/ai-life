package dev.fedorov.ailife.contracts.tasks;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskItemDto(
        UUID id,
        UUID householdId,
        UUID ownerId,
        UUID projectId,
        String title,
        String status,
        String context,
        Integer priority,
        Instant dueAt,
        Instant deferUntil,
        String note,
        String source,
        String externalRef,
        String calendarEventUid,
        UUID scheduleId,
        Instant createdAt,
        Instant completedAt) {
}
