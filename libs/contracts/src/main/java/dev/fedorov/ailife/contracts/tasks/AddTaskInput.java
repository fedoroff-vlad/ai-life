package dev.fedorov.ailife.contracts.tasks;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Capture a task to the GTD inbox. Only {@code householdId} + {@code title} are required —
 * a fresh capture lands as status=inbox with no project/context; clarification comes later
 * (clarify_task). {@code source} defaults to "manual".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AddTaskInput(
        UUID householdId,
        UUID ownerId,
        String title,
        String note,
        String source) {
}
