package dev.fedorov.ailife.contracts.tasks;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Body for tasks-agent's {@code POST /agents/tasks/internal/task-to-event} — turns a
 * hard-deadline task into a calendar event (Stage 4 / C1). tasks-agent asks calendar-agent
 * (via the orchestrator) to create the event, then records the returned UID on the task.
 * One-direction MVP (task → event).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskToEventRequest(
        UUID taskId,
        UUID householdId,
        String summary,
        Instant dueAt) {
}
