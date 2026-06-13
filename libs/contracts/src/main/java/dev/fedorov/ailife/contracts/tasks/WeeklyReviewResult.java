package dev.fedorov.ailife.contracts.tasks;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * Aggregated snapshot a GTD weekly review works from: the un-clarified inbox, delegated
 * waiting-for items, and "stuck" projects (active, but with no next-action). Counts are reported
 * separately from the (capped) sample lists so the skill can say "12 inbox items" even when only
 * the first N are listed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WeeklyReviewResult(
        UUID householdId,
        int inboxCount,
        int waitingCount,
        int stuckProjectCount,
        List<TaskItemDto> inbox,
        List<TaskItemDto> waiting,
        List<TaskProjectDto> stuckProjects) {
}
