package dev.fedorov.ailife.mcp.tasks.web;

import dev.fedorov.ailife.contracts.tasks.ListTasksInput;
import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import dev.fedorov.ailife.mcp.tasks.tools.TasksMcpTools;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Non-MCP REST passthrough for a filtered task list — for deterministic system callers (no LLM
 * tax). {@code GET /internal/tasks?householdId=&status=&context=&dueBefore=&limit=} mirrors the
 * {@code list_tasks} tool's filters and delegates straight to it, so every invariant (household
 * scope, ordering, hard cap) applies identically. Used by tasks-agent's intent skills
 * (e.g. {@code next-action-suggester} fetching open next-actions). Mirrors
 * {@link InternalReviewController} and mcp-finance's {@code /internal/*} passthroughs.
 */
@RestController
public class InternalTaskController {

    private final TasksMcpTools tools;

    public InternalTaskController(TasksMcpTools tools) {
        this.tools = tools;
    }

    @GetMapping("/internal/tasks")
    public List<TaskItemDto> tasks(@RequestParam UUID householdId,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) String context,
                                   @RequestParam(required = false) Instant dueBefore,
                                   @RequestParam(required = false) Integer limit) {
        return tools.listTasks(new ListTasksInput(householdId, status, context, null, dueBefore, limit));
    }
}
