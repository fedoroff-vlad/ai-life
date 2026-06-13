package dev.fedorov.ailife.mcp.tasks.review;

import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import dev.fedorov.ailife.contracts.tasks.TaskProjectDto;
import dev.fedorov.ailife.contracts.tasks.WeeklyReviewResult;
import dev.fedorov.ailife.mcp.tasks.domain.TaskItem;
import dev.fedorov.ailife.mcp.tasks.domain.TaskItemRepository;
import dev.fedorov.ailife.mcp.tasks.domain.TaskProject;
import dev.fedorov.ailife.mcp.tasks.domain.TaskProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Builds the GTD weekly-review snapshot for a household: full counts of the inbox and waiting-for
 * lists plus capped samples, and the "stuck" active projects (no next-action). Read-only; the
 * tasks-agent's {@code weekly-review} skill turns this into prose.
 */
@Service
public class ReviewService {

    /** Sample size per list — the skill cites the full count but only lists the first few. */
    private static final int SAMPLE_CAP = 25;

    private final TaskItemRepository items;
    private final TaskProjectRepository projects;

    public ReviewService(TaskItemRepository items, TaskProjectRepository projects) {
        this.items = items;
        this.projects = projects;
    }

    @Transactional(readOnly = true)
    public WeeklyReviewResult review(UUID householdId) {
        int inboxCount = items.countByHouseholdIdAndStatus(householdId, "inbox");
        int waitingCount = items.countByHouseholdIdAndStatus(householdId, "waiting");

        List<TaskItemDto> inbox = items.filter(householdId, "inbox", null, null, null, SAMPLE_CAP)
                .stream().map(TaskItem::toDto).toList();
        List<TaskItemDto> waiting = items.filter(householdId, "waiting", null, null, null, SAMPLE_CAP)
                .stream().map(TaskItem::toDto).toList();
        List<TaskProjectDto> stuck = projects.findActiveWithoutNextAction(householdId)
                .stream().map(TaskProject::toDto).toList();

        return new WeeklyReviewResult(householdId, inboxCount, waitingCount, stuck.size(),
                inbox, waiting, stuck);
    }
}
