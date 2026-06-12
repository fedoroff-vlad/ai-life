package dev.fedorov.ailife.contracts.tasks;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Partial content edit of an existing task. {@code id} is required; every other field is applied
 * only when non-null (null = leave unchanged), so it can fix a title / re-context / re-schedule but
 * cannot clear an already-set field. Status changes go through clarify_task / complete_task, not
 * here. {@code householdId} / {@code source} / {@code externalRef} / {@code createdAt} are immutable.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpdateTaskInput(
        UUID id,
        String title,
        String note,
        String context,
        UUID projectId,
        Integer priority,
        Instant dueAt,
        Instant deferUntil) {
}
