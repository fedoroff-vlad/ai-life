package dev.fedorov.ailife.contracts.tasks;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * The GTD inbox→organized transition. {@code id} + {@code status} are required; the organizing
 * fields ({@code context}, {@code projectId}, {@code dueAt}, {@code deferUntil}, {@code priority})
 * are applied when non-null. {@code status} is one of inbox|next|waiting|scheduled|done|dropped.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClarifyTaskInput(
        UUID id,
        String status,
        String context,
        UUID projectId,
        Instant dueAt,
        Instant deferUntil,
        Integer priority) {
}
