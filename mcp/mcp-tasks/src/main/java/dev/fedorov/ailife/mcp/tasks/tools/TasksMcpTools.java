package dev.fedorov.ailife.mcp.tasks.tools;

import dev.fedorov.ailife.contracts.tasks.AddTaskInput;
import dev.fedorov.ailife.contracts.tasks.ClarifyTaskInput;
import dev.fedorov.ailife.contracts.tasks.ListTasksInput;
import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import dev.fedorov.ailife.contracts.tasks.TaskProjectDto;
import dev.fedorov.ailife.contracts.tasks.UpdateTaskInput;
import dev.fedorov.ailife.contracts.tasks.UpsertProjectInput;
import dev.fedorov.ailife.mcp.tasks.domain.TaskItem;
import dev.fedorov.ailife.mcp.tasks.domain.TaskItemRepository;
import dev.fedorov.ailife.mcp.tasks.domain.TaskProject;
import dev.fedorov.ailife.mcp.tasks.domain.TaskProjectRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Stage 3 opener: starter GTD CRUD over tasks.* (projects + items). The GTD transitions
 * (clarify_task, complete_task, update_task, delete_task, link_task_to_event) land in the
 * next PR; this slice covers capture (add_task → inbox), read (list_tasks) and projects.
 *
 * Scope rule: every tool takes a householdId and reads/writes only within that household.
 * Per-user privacy (owner_id filtering for "private" items) is the agent layer's job — this
 * MCP is intentionally low-level (mirrors mcp-finance).
 */
@Component
public class TasksMcpTools {

    private static final int LIST_HARD_CAP = 200;
    private static final Set<String> VALID_STATUSES =
            Set.of("inbox", "next", "waiting", "scheduled", "done", "dropped");

    private final TaskProjectRepository projects;
    private final TaskItemRepository items;

    public TasksMcpTools(TaskProjectRepository projects, TaskItemRepository items) {
        this.projects = projects;
        this.items = items;
    }

    @Tool(description = """
            Create or update a GTD project (a multi-step outcome, e.g. "Plan vacation").
            If `id` is supplied an existing row is updated in place; otherwise a new row is
            created with a fresh UUID. `status` is one of active|someday|done|dropped
            (defaults to "active"). Set `ownerId` for a private project; leave null for a
            shared household project.
            """)
    @Transactional
    public TaskProjectDto upsertProject(UpsertProjectInput input) {
        requireField(input.householdId(), "householdId");
        requireField(input.name(), "name");
        String status = (input.status() == null || input.status().isBlank())
                ? "active" : input.status();
        TaskProject entity = input.id() == null
                ? new TaskProject(UUID.randomUUID(), input.householdId(), input.ownerId(),
                        input.name(), status, input.note())
                : projects.findById(input.id()).orElseThrow(
                        () -> new IllegalArgumentException("Project not found: " + input.id()));
        if (input.id() != null) {
            entity.setOwnerId(input.ownerId());
            entity.setName(input.name());
            entity.setStatus(status);
            entity.setNote(input.note());
        }
        return projects.save(entity).toDto();
    }

    @Tool(description = """
            List GTD projects in a household, ordered by name. Pass `status` to filter
            (active|someday|done|dropped); omit it to list every project.
            """)
    @Transactional(readOnly = true)
    public List<TaskProjectDto> listProjects(UUID householdId, String status) {
        List<TaskProject> rows = (status == null || status.isBlank())
                ? projects.findByHouseholdIdOrderByName(householdId)
                : projects.findByHouseholdIdAndStatusOrderByName(householdId, status);
        return rows.stream().map(TaskProject::toDto).toList();
    }

    @Tool(description = """
            Capture a task to the GTD inbox. This is the one-line capture GTD lives on:
            only `householdId` + `title` are needed — the item lands as status=inbox with
            no project/context, to be clarified later. `source` defaults to "manual" (pass
            "telegram" etc. for provenance). Set `ownerId` for a private task.
            """)
    @Transactional
    public TaskItemDto addTask(AddTaskInput input) {
        requireField(input.householdId(), "householdId");
        requireField(input.title(), "title");
        String source = (input.source() == null || input.source().isBlank())
                ? "manual" : input.source();
        TaskItem entity = new TaskItem(UUID.randomUUID(), input.householdId(), input.ownerId(),
                input.title(), "inbox", input.note(), source);
        return items.save(entity).toDto();
    }

    @Tool(description = """
            List tasks in a household. All filters are optional: `status`
            (inbox|next|waiting|scheduled|done|dropped), `context` (e.g. @home), `projectId`,
            `dueBefore` (exclusive). Ordered by due date soonest-first (undated last). The
            `limit` is capped at 200; default 50.
            """)
    @Transactional(readOnly = true)
    public List<TaskItemDto> listTasks(ListTasksInput input) {
        requireField(input.householdId(), "householdId");
        int limit = input.limit() == null ? 50 : Math.min(input.limit(), LIST_HARD_CAP);
        return items.filter(
                        input.householdId(),
                        input.status(),
                        input.context(),
                        input.projectId(),
                        input.dueBefore(),
                        limit).stream()
                .map(TaskItem::toDto)
                .toList();
    }

    @Tool(description = """
            Clarify an inbox task into an organized GTD state — the core inbox→organized
            move. `id` and `status` are required (status ∈
            inbox|next|waiting|scheduled|done|dropped). The organizing fields `context`
            (e.g. @home), `projectId`, `dueAt`, `deferUntil`, `priority` are applied when
            non-null. A `projectId` must belong to the task's household. Setting status to
            "done" stamps completedAt; moving away from "done" clears it.
            """)
    @Transactional
    public TaskItemDto clarifyTask(ClarifyTaskInput input) {
        requireField(input.id(), "id");
        requireField(input.status(), "status");
        requireStatus(input.status());
        TaskItem item = items.findById(input.id()).orElseThrow(
                () -> new IllegalArgumentException("Task not found: " + input.id()));
        if (input.projectId() != null) {
            requireProjectInHousehold(input.projectId(), item.getHouseholdId());
            item.setProjectId(input.projectId());
        }
        if (input.context() != null) item.setContext(input.context());
        if (input.dueAt() != null) item.setDueAt(input.dueAt());
        if (input.deferUntil() != null) item.setDeferUntil(input.deferUntil());
        if (input.priority() != null) item.setPriority(input.priority());
        applyStatus(item, input.status());
        return items.save(item).toDto();
    }

    @Tool(description = """
            Partial content edit of a task. `id` is required; every other field is applied
            only when non-null (null = leave unchanged), so this fixes a title / re-contexts
            / re-schedules but cannot clear a set field. Status changes go through
            clarify_task or complete_task, not here. A `projectId` must belong to the task's
            household. household/source/externalRef/createdAt are immutable.
            """)
    @Transactional
    public TaskItemDto updateTask(UpdateTaskInput input) {
        requireField(input.id(), "id");
        TaskItem item = items.findById(input.id()).orElseThrow(
                () -> new IllegalArgumentException("Task not found: " + input.id()));
        if (input.projectId() != null) {
            requireProjectInHousehold(input.projectId(), item.getHouseholdId());
            item.setProjectId(input.projectId());
        }
        if (input.title() != null && !input.title().isBlank()) item.setTitle(input.title());
        if (input.note() != null) item.setNote(input.note());
        if (input.context() != null) item.setContext(input.context());
        if (input.priority() != null) item.setPriority(input.priority());
        if (input.dueAt() != null) item.setDueAt(input.dueAt());
        if (input.deferUntil() != null) item.setDeferUntil(input.deferUntil());
        return items.save(item).toDto();
    }

    @Tool(description = """
            Mark a task done — sets status=done and stamps completedAt. Shortcut for the
            common terminal transition. Throws if the id is unknown.
            """)
    @Transactional
    public TaskItemDto completeTask(UUID id) {
        requireField(id, "id");
        TaskItem item = items.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Task not found: " + id));
        applyStatus(item, "done");
        return items.save(item).toDto();
    }

    @Tool(description = """
            Delete a task and return the deleted row (so the agent can confirm / offer undo).
            Throws if the id is unknown. Confirming the destructive action with the user is
            the agent layer's job.
            """)
    @Transactional
    public TaskItemDto deleteTask(UUID id) {
        requireField(id, "id");
        TaskItem item = items.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Task not found: " + id));
        TaskItemDto dto = item.toDto();
        items.delete(item);
        return dto;
    }

    @Tool(description = """
            Link a task to a calendar event ("turn task into an event"). Stores the event's
            calendar UID on the task; creating the actual calendar event is the agent's job
            (via orchestrator → calendar-agent). Throws if the id is unknown.
            """)
    @Transactional
    public TaskItemDto linkTaskToEvent(UUID id, String calendarEventUid) {
        requireField(id, "id");
        requireField(calendarEventUid, "calendarEventUid");
        TaskItem item = items.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Task not found: " + id));
        item.setCalendarEventUid(calendarEventUid);
        return items.save(item).toDto();
    }

    /** Apply a status transition, keeping completedAt consistent with the done state. */
    private static void applyStatus(TaskItem item, String status) {
        item.setStatus(status);
        if ("done".equals(status)) {
            if (item.getCompletedAt() == null) item.setCompletedAt(Instant.now());
        } else {
            item.setCompletedAt(null);
        }
    }

    private void requireProjectInHousehold(UUID projectId, UUID householdId) {
        TaskProject project = projects.findById(projectId).orElseThrow(
                () -> new IllegalArgumentException("Project not found: " + projectId));
        if (!project.getHouseholdId().equals(householdId)) {
            throw new IllegalArgumentException(
                    "Project does not belong to household: " + projectId);
        }
    }

    private static void requireStatus(String status) {
        if (!VALID_STATUSES.contains(status)) {
            throw new IllegalArgumentException(
                    "Unsupported status: " + status + " (expected one of " + VALID_STATUSES + ")");
        }
    }

    private static void requireField(Object value, String name) {
        if (value == null) throw new IllegalArgumentException("Missing required field: " + name);
        if (value instanceof String s && s.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + name);
        }
    }
}
