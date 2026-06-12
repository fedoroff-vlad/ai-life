package dev.fedorov.ailife.contracts.tasks;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Filtered task list. All filters optional (null = don't filter): {@code status},
 * {@code context}, {@code projectId}, {@code dueBefore} (exclusive). {@code limit} is
 * capped server-side.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListTasksInput(
        UUID householdId,
        String status,
        String context,
        UUID projectId,
        Instant dueBefore,
        Integer limit) {
}
